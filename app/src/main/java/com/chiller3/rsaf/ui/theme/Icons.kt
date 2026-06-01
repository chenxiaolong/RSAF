/*
 * SPDX-FileCopyrightText: Google
 * SPDX-License-Identifier: Apache-2.0
 *
 * All icons here originated from Material Symbols: https://fonts.google.com/icons
 */

package com.chiller3.rsaf.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object Icons {
    object AutoMirrored {
        // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/arrow_back.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
        val ArrowBack: ImageVector
            get() {
                if (_ArrowBack != null) {
                    return _ArrowBack!!
                }
                _ArrowBack =
                    ImageVector.Builder(
                        name = "arrow_back",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                        autoMirror = true,
                    )
                        .apply {
                            path(
                                fill = SolidColor(Color.Black),
                                fillAlpha = 1f,
                                stroke = null,
                                strokeAlpha = 1f,
                                strokeLineWidth = 1f,
                                strokeLineCap = StrokeCap.Butt,
                                strokeLineJoin = StrokeJoin.Bevel,
                                strokeLineMiter = 1f,
                                pathFillType = PathFillType.NonZero,
                            ) {
                                moveTo(7.83f, 13f)
                                lineToRelative(5.6f, 5.6f)
                                lineTo(12f, 20f)
                                lineTo(4f, 12f)
                                lineTo(12f, 4f)
                                lineToRelative(1.43f, 1.4f)
                                lineTo(7.83f, 11f)
                                horizontalLineTo(20f)
                                verticalLineToRelative(2f)
                                horizontalLineTo(7.83f)
                                close()
                            }
                        }
                        .build()
                return _ArrowBack!!
            }

        private var _ArrowBack: ImageVector? = null
    }

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/check.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val Check: ImageVector
        get() {
            if (_Check != null) {
                return _Check!!
            }
            _Check =
                ImageVector.Builder(
                    name = "check",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(9.55f, 18f)
                            lineTo(3.85f, 12.3f)
                            lineTo(5.28f, 10.88f)
                            lineToRelative(4.28f, 4.28f)
                            lineTo(18.73f, 5.97f)
                            lineTo(20.15f, 7.4f)
                            lineTo(9.55f, 18f)
                            close()
                        }
                    }
                    .build()
            return _Check!!
        }

    private var _Check: ImageVector? = null

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/close.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val Close: ImageVector
        get() {
            if (_Close != null) {
                return _Close!!
            }
            _Close =
                ImageVector.Builder(
                    name = "close",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(6.4f, 19f)
                            lineTo(5f, 17.6f)
                            lineTo(10.6f, 12f)
                            lineTo(5f, 6.4f)
                            lineTo(6.4f, 5f)
                            lineTo(12f, 10.6f)
                            lineTo(17.6f, 5f)
                            lineTo(19f, 6.4f)
                            lineTo(13.4f, 12f)
                            lineTo(19f, 17.6f)
                            lineTo(17.6f, 19f)
                            lineTo(12f, 13.4f)
                            lineTo(6.4f, 19f)
                            close()
                        }
                    }
                    .build()
            return _Close!!
        }

    private var _Close: ImageVector? = null

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/visibility.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val Visibility: ImageVector
        get() {
            if (_Visibility != null) {
                return _Visibility!!
            }
            _Visibility =
                ImageVector.Builder(
                    name = "visibility",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(15.19f, 14.69f)
                            quadTo(16.5f, 13.38f, 16.5f, 11.5f)
                            reflectiveQuadTo(15.19f, 8.31f)
                            reflectiveQuadTo(12f, 7f)
                            reflectiveQuadTo(8.81f, 8.31f)
                            reflectiveQuadTo(7.5f, 11.5f)
                            reflectiveQuadToRelative(1.31f, 3.19f)
                            reflectiveQuadTo(12f, 16f)
                            reflectiveQuadToRelative(3.19f, -1.31f)
                            close()
                            moveToRelative(-5.1f, -1.28f)
                            quadTo(9.3f, 12.63f, 9.3f, 11.5f)
                            reflectiveQuadTo(10.09f, 9.59f)
                            reflectiveQuadTo(12f, 8.8f)
                            reflectiveQuadToRelative(1.91f, 0.79f)
                            quadToRelative(0.79f, 0.79f, 0.79f, 1.91f)
                            reflectiveQuadToRelative(-0.79f, 1.91f)
                            reflectiveQuadTo(12f, 14.2f)
                            reflectiveQuadTo(10.09f, 13.41f)
                            close()
                            moveTo(5.35f, 16.96f)
                            quadTo(2.35f, 14.93f, 1f, 11.5f)
                            quadTo(2.35f, 8.07f, 5.35f, 6.04f)
                            reflectiveQuadTo(12f, 4f)
                            reflectiveQuadToRelative(6.65f, 2.04f)
                            reflectiveQuadTo(23f, 11.5f)
                            quadToRelative(-1.35f, 3.42f, -4.35f, 5.46f)
                            reflectiveQuadTo(12f, 19f)
                            reflectiveQuadTo(5.35f, 16.96f)
                            close()
                            moveTo(12f, 11.5f)
                            close()
                            moveToRelative(5.19f, 4.01f)
                            quadTo(19.55f, 14.02f, 20.8f, 11.5f)
                            quadTo(19.55f, 8.98f, 17.19f, 7.49f)
                            reflectiveQuadTo(12f, 6f)
                            quadTo(9.18f, 6f, 6.81f, 7.49f)
                            reflectiveQuadTo(3.2f, 11.5f)
                            quadToRelative(1.25f, 2.52f, 3.61f, 4.01f)
                            reflectiveQuadTo(12f, 17f)
                            reflectiveQuadToRelative(5.19f, -1.49f)
                            close()
                        }
                    }
                    .build()
            return _Visibility!!
        }

    private var _Visibility: ImageVector? = null

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/visibility_off.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val VisibilityOff: ImageVector
        get() {
            if (_VisibilityOff != null) {
                return _VisibilityOff!!
            }
            _VisibilityOff =
                ImageVector.Builder(
                    name = "visibility_off",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(16.1f, 13.3f)
                            lineTo(14.65f, 11.85f)
                            quadToRelative(0.22f, -1.18f, -0.67f, -2.2f)
                            quadTo(13.08f, 8.63f, 11.65f, 8.85f)
                            lineTo(10.2f, 7.4f)
                            quadTo(10.63f, 7.2f, 11.06f, 7.1f)
                            reflectiveQuadTo(12f, 7f)
                            quadToRelative(1.88f, 0f, 3.19f, 1.31f)
                            reflectiveQuadTo(16.5f, 11.5f)
                            quadToRelative(0f, 0.5f, -0.1f, 0.94f)
                            reflectiveQuadTo(16.1f, 13.3f)
                            close()
                            moveToRelative(3.2f, 3.15f)
                            lineToRelative(-1.45f, -1.4f)
                            quadToRelative(0.95f, -0.72f, 1.69f, -1.59f)
                            reflectiveQuadTo(20.8f, 11.5f)
                            quadTo(19.55f, 8.98f, 17.21f, 7.49f)
                            reflectiveQuadTo(12f, 6f)
                            quadTo(11.28f, 6f, 10.58f, 6.1f)
                            reflectiveQuadTo(9.2f, 6.4f)
                            lineTo(7.65f, 4.85f)
                            quadTo(8.68f, 4.42f, 9.75f, 4.21f)
                            reflectiveQuadTo(12f, 4f)
                            quadToRelative(3.78f, 0f, 6.73f, 2.09f)
                            reflectiveQuadTo(23f, 11.5f)
                            quadToRelative(-0.57f, 1.47f, -1.51f, 2.74f)
                            reflectiveQuadTo(19.3f, 16.45f)
                            close()
                            moveToRelative(0.5f, 6.15f)
                            lineTo(15.6f, 18.45f)
                            quadToRelative(-0.88f, 0.28f, -1.76f, 0.41f)
                            reflectiveQuadTo(12f, 19f)
                            quadTo(8.23f, 19f, 5.28f, 16.91f)
                            reflectiveQuadTo(1f, 11.5f)
                            quadTo(1.53f, 10.17f, 2.33f, 9.04f)
                            reflectiveQuadTo(4.15f, 7f)
                            lineTo(1.4f, 4.2f)
                            lineTo(2.8f, 2.8f)
                            lineTo(21.2f, 21.2f)
                            lineToRelative(-1.4f, 1.4f)
                            close()
                            moveTo(5.55f, 8.4f)
                            quadTo(4.83f, 9.05f, 4.23f, 9.82f)
                            reflectiveQuadTo(3.2f, 11.5f)
                            quadToRelative(1.25f, 2.52f, 3.59f, 4.01f)
                            reflectiveQuadTo(12f, 17f)
                            quadToRelative(0.5f, 0f, 0.98f, -0.06f)
                            reflectiveQuadTo(13.95f, 16.8f)
                            lineToRelative(-0.9f, -0.95f)
                            quadToRelative(-0.28f, 0.07f, -0.53f, 0.11f)
                            reflectiveQuadTo(12f, 16f)
                            quadTo(10.13f, 16f, 8.81f, 14.69f)
                            reflectiveQuadTo(7.5f, 11.5f)
                            quadToRelative(0f, -0.28f, 0.04f, -0.53f)
                            reflectiveQuadTo(7.65f, 10.45f)
                            lineTo(5.55f, 8.4f)
                            close()
                            moveToRelative(7.98f, 2.33f)
                            close()
                            moveTo(9.75f, 12.6f)
                            close()
                        }
                    }
                    .build()
            return _VisibilityOff!!
        }

    private var _VisibilityOff: ImageVector? = null
}
