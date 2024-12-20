/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.rclone

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Helper functions for creating thumbnails of audio, image, and video files. It is similar to
 * [ThumbnailUtils], except:
 *
 * * the source is a file descriptor
 * * no filesystem traversals are performed
 * * there are more cancellation points
 * * EXIF image flip orientation tag values are supported
 * * the more capable [ExifInterface] androidx library is used
 */
object Thumbnailer {
    private const val PRE_Q_MEM_SIZE_LIMIT = 8L * 1024 * 1024

    /** Set desired image scale before decoding to reduce memory usage. */
    private class SubsampleDuringDecode(
        private val size: Size,
        private val signal: CancellationSignal?,
    ) : ImageDecoder.OnHeaderDecodedListener {
        override fun onHeaderDecoded(
            decoder: ImageDecoder,
            info: ImageDecoder.ImageInfo,
            source: ImageDecoder.Source,
        ) {
            signal?.throwIfCanceled()

            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)

            val widthScale = info.size.width / size.width
            val heightScale = info.size.height / size.height
            val inverseScale = max(widthScale, heightScale)
            if (inverseScale > 1) {
                decoder.setTargetSampleSize(inverseScale)
            }
        }
    }

    /** Check whether thumbnail generation is theoretically supported for a MIME type. */
    fun isSupported(mimeType: String): Boolean =
        mimeType.startsWith("audio/")
                || mimeType.startsWith("image/")
                || mimeType.startsWith("video/")

    /** Check whether an image MIME type is stored as one or more frames in a video container. */
    private fun isImageInVideoContainer(mimeType: String): Boolean =
        mimeType == "image/avif"
                || mimeType == "image/heic"
                || mimeType == "image/heic-sequence"
                || mimeType == "image/heif"
                || mimeType == "image/heif-sequence"

    /**
     * Get the embedded thumbnail from an audio file. Throws [FileNotFoundException] if the file
     * does not have an embedded thumbnail.
     */
    private fun createAudioThumbnail(
        fd: FileDescriptor,
        sizeHint: Size,
        signal: CancellationSignal?,
    ): Bitmap {
        signal?.throwIfCanceled()

        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(fd)

            val embedded = retriever.embeddedPicture
                ?: throw FileNotFoundException("Audio file has no embedded thumbnail")

            signal?.throwIfCanceled()

            val source = ImageDecoder.createSource(ByteBuffer.wrap(embedded))
            val subsampler = SubsampleDuringDecode(sizeHint, signal)

            return ImageDecoder.decodeBitmap(source, subsampler)
        } finally {
            retriever.release()
        }

        throw FileNotFoundException("Audio file has no embedded thumbnail")
    }

    /**
     * Get the embedded thumbnail from an image file. If the file does not have one, then the image
     * itself is used to generate a thumbnail. On Android P, this fallback path requires reading the
     * entire file into memory, so an [IOException] will be thrown if the file size exceeds a limit.
     * If the image file format is stored in a video container, then the first image in the video
     * container is used to create the thumbnail.
     */
    private fun createImageThumbnail(
        fd: FileDescriptor,
        mimeType: String,
        sizeHint: Size,
        signal: CancellationSignal?,
    ): Bitmap {
        var bitmap: Bitmap? = null

        // Try to extract the embedded thumbnail from image formats stored in video containers.
        if (isImageInVideoContainer(mimeType)) {
            signal?.throwIfCanceled()

            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(fd)

                // getThumbnailImageAtIndex() is not on the hidden API whitelist, so we'll have to
                // use getImageAtIndex() instead and do the downscaling ourselves.
                val image = retriever.getImageAtIndex(-1)
                if (image != null) {
                    signal?.throwIfCanceled()

                    bitmap = ThumbnailUtils.extractThumbnail(image, sizeHint.width, sizeHint.height)
                    if (bitmap !== image) {
                        image.recycle()
                    }
                }
            } finally {
                retriever.release()
            }
        }

        signal?.throwIfCanceled()

        // This dup()s the fd and uses its own copy when parsing the file. For HEIF, this will
        // internally parse the file again with MediaMetadataRetriever. There is no way to prevent
        // this from happening.
        val exif = ExifInterface(fd)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )

        val (rotation, scaleX, scaleY) = when (orientation) {
            ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> Triple(0, 1, 1)
            ExifInterface.ORIENTATION_ROTATE_90 -> Triple(90, 1, 1)
            ExifInterface.ORIENTATION_ROTATE_180 -> Triple(180, 1, 1)
            ExifInterface.ORIENTATION_ROTATE_270 -> Triple(270, 1, 1)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Triple(0, -1, 1)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Triple(0, 1, -1)
            ExifInterface.ORIENTATION_TRANSPOSE -> Triple(90, -1, 0)
            ExifInterface.ORIENTATION_TRANSVERSE -> Triple(270, -1, 0)
            else -> throw IllegalStateException("Invalid orientation tag value: $orientation")
        }

        val subsampler = SubsampleDuringDecode(sizeHint, signal)

        // Try to extract the thumbnail embedded in the EXIF data.
        if (bitmap == null) {
            val thumbnail = exif.thumbnailBytes
            if (thumbnail != null) {
                signal?.throwIfCanceled()

                val source = ImageDecoder.createSource(ByteBuffer.wrap(thumbnail))

                try {
                    bitmap = ImageDecoder.decodeBitmap(source, subsampler)
                } catch (_: ImageDecoder.DecodeException) {
                    // Ignore
                }
            }
        }

        // We need to manually apply rotation and X/Y flips since the EXIF orientation tag is not
        // processed when loading embedded thumbnails.
        if (bitmap != null) {
            val needRotation = rotation != 0
            val needScale = scaleX != 1 || scaleY != 1

            if (needRotation || needScale) {
                signal?.throwIfCanceled()

                val centerX = (bitmap.width / 2).toFloat()
                val centerY = (bitmap.height / 2).toFloat()
                val matrix = Matrix()

                if (needRotation) {
                    matrix.setRotate(rotation.toFloat(), centerX, centerY)
                }
                if (needScale) {
                    matrix.postScale(scaleX.toFloat(), scaleY.toFloat(), centerX, centerY)
                }

                val newBitmap =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                if (newBitmap !== bitmap) {
                    bitmap.recycle()
                }

                bitmap = newBitmap
            }
        }

        // If there is no embedded thumbnail, then we'll create one from the image. This will
        // automatically process the EXIF orientation tag.
        if (bitmap == null) {
            signal?.throwIfCanceled()

            val fileSize = Os.lseek(fd, 0, OsConstants.SEEK_END)

            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageDecoder.createSource {
                    AssetFileDescriptor(ParcelFileDescriptor.dup(fd), 0, fileSize)
                }
            } else if (fileSize <= PRE_Q_MEM_SIZE_LIMIT) {
                // Android P has no way to read from an arbitrary file descriptor, so we'll have to
                // read things into memory. We'll only do this if the file is small.
                val buffer = ByteBuffer.allocate(fileSize.toInt())

                val n = Os.pread(fd, buffer, 0)
                if (n != buffer.limit()) {
                    throw IOException("Unexpected EOF: $n != $fileSize")
                }

                ImageDecoder.createSource(buffer)
            } else {
                throw IOException("Image too large to read into memory: $fileSize")
            }

            bitmap = ImageDecoder.decodeBitmap(source, subsampler)
        }

        return bitmap
    }

    /**
     * Get the embedded thumbnail from a video file. If the file does not have one, then a thumbnail
     * is created from the closest keyframe at the half point of the video.
     */
    private fun createVideoThumbnail(
        fd: FileDescriptor,
        sizeHint: Size,
        signal: CancellationSignal?,
    ): Bitmap {
        signal?.throwIfCanceled()

        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(fd)

            val embedded = retriever.embeddedPicture
            if (embedded != null) {
                signal?.throwIfCanceled()

                val source = ImageDecoder.createSource(ByteBuffer.wrap(embedded))
                val subsampler = SubsampleDuringDecode(sizeHint, signal)

                return ImageDecoder.decodeBitmap(source, subsampler)
            }

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toInt() ?: throw IOException("Failed to get video width")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toInt() ?: throw IOException("Failed to get video height")
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong() ?: throw IOException("Failed to get video duration")

            val thumbnailUs = durationMs * 1000 / 2
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC

            signal?.throwIfCanceled()

            return if (sizeHint.width >= width && sizeHint.height >= height) {
                retriever.getFrameAtTime(thumbnailUs, option)
            } else {
                retriever.getScaledFrameAtTime(thumbnailUs, option, sizeHint.width, sizeHint.height)
            } ?: throw IOException("Failed to extract frame from video")
        } finally {
            retriever.release()
        }
    }

    /**
     * Get the thumbnail of an audio, image, or video file. Throws [IllegalArgumentException] if
     * [mimeType] does not refer to an audio, image, or video MIME type.
     */
    fun createThumbnail(
        fd: FileDescriptor,
        mimeType: String,
        sizeHint: Size,
        signal: CancellationSignal?,
    ): Bitmap {
        return if (mimeType.startsWith("audio/")) {
            createAudioThumbnail(fd, sizeHint, signal)
        } else if (mimeType.startsWith("image/")) {
            createImageThumbnail(fd, mimeType, sizeHint, signal)
        } else if (mimeType.startsWith("video/")) {
            createVideoThumbnail(fd, sizeHint, signal)
        } else {
            throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
    }
}
