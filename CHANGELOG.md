<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

* Use local pinned version of gomobile during build ([PR #135])
* Add the machine-readable version code to the APK filename ([Issue #50], [PR #136])
* Make gomobile shared library reproducible ([PR #138])

### Version 3.3

* Add debug option to add an `alias` remote that points to rclone's internal cache directory ([PR #133])
  * This is useful for inspecting rclone's `vfsMeta` JSON files that describe the state of pending file uploads.

### Version 3.2

* Update rclone to 1.69.1 ([PR #130])

### Version 3.1

* Add support for user CA certificate trust store and Android 14+'s apex trust store ([Issue #119], [PR #120], [PR #125])
* Make file close operations synchronous after VFS initialization ([PR #121])
  * This fixes a regression caused by [PR #114] in version 3.0 that reintroduced [Issue #81].
  * This allows RSAF to report upload errors in most cases again.
* Ask for confirmation before performing destructive operations on a remote that has pending uploads ([PR #123])
  * This includes deleting a remote, renaming a remote, or importing a config file.
* Only perform copy and move operations via rclone if they can be done server-side ([PR #126])
  * Android does not allow file copy/move operations to be slow and will kill RSAF if they are.
  * For non-server-side copies/moves, RSAF will report that the operation is unsupported and the file manager will handle it itself instead.
* Fix revoking access to the original path when a directory is moved or deleted ([PR #129])

### Version 3.0

* Add support for resuming background uploads if RSAF crashes or is killed by Android ([PR #114])
  * This process is automatically triggered after the device boots or when RSAF is opened.
  * This also fixes an issue where after a crash, the first file operation on a remote will hang until all leftover pending uploads for that remote have completed.
  * **NOTE**: Due to limitations with how rclone reports errors for asynchronous uploads, RSAF is no longer able to show a notification if a file upload fails. rclone automatically retries failed uploads and the files remain in the VFS cache until the upload succeeds.
* Remove support for POSIX-like file operation semantics ([PR #115])
  * Android is generally not designed to behave this way and applications expect document providers, like RSAF, to behave like Android's builtin document provider for local files. There are no known client applications that explicitly made use of this feature.
* Fix caching of golang dependencies in Github Actions CI builds ([PR #116])
* Update rclone to 1.69.0 ([PR #117])
* Update dependencies ([PR #118])

### Version 2.5

* Add support for generating thumbnails for audio, image, and video files ([PR #113])

### Version 2.4

* Make inactivity timeout duration for the app lock configurable and add a button for locking immediately ([PR #109])
* Add support for hiding files on a remote while the app is locked ([Issue #106], [PR #110])
* Update dependencies ([PR #111])

### Version 2.3

* Fix icon scale and rebase off latest material smb share icon ([PR #105])
* Suppress errors when closing read-only files ([PR #107])
* Show notification when files are open to keep the process alive ([PR #108])
  * This reduces the chance of RSAF being suspended or killed, for example, when watching videos
  * Android's restrictions require RSAF to attempt to show the notification, but the notification channel can be disabled from Android's settings with no negative side effects

### Version 2.2

* Update rclone to 1.68.2 ([PR #103])
* Update gradle dependencies ([PR #104])

### Version 2.1

* Allow retries when biometric auth fails the first time, instead of just failing and exiting ([PR #98])
* Add support for reporting filesystem usage (total and available space) to client apps ([PR #99])
  * This is configured per-remote and is disabled by default because some remote types (eg. Google Drive) are extremely slow at computing the numbers

### Version 2.0

* Organize source code files into packages ([PR #78])
* Make a proper settings screen for editing remotes instead of just using a dialog box ([PR #80])
* Enable predictive back gestures ([PR #82])
* Make biometric authentication option more robust ([PR #83], [PR #92])
* Fix UI jank when rotating the device caused by hiding already-granted permission requests too late ([PR #84])
* Sort remote types by description when adding a new remote to match the rclone CLI ([PR #85])
* Add support for disabling VFS caching per-remote ([Issue #79], [PR #86])
* Add progress details to background upload notification ([Issue #79], [PR #87])
* Increase chance of the post-upload VFS cache directory cleanup finishing before Android stops RSAF ([PR #88])
* Avoid spawning the background upload monitor service for a split second when closing a read-only file ([PR #89])
* Work around Android limitations to make client apps that rename, copy, or move a newly written file work more reliably ([Issue #81], [PR #90])
* Prevent background upload notifications from rapidly appearing and disappearing when writing many small files ([PR #91])
* Fix biometric and device credential authentication on Android <11 ([Issue #93], [PR #94])
* Work around an Android bug in Android <11 that causes the edge-to-edge layout to not account for the navigation bar ([Issue #93], [PR #95])
* Fix potential issue with crash handler itself crashing when attempting to write a log file ([PR #96])
* Allow importing any file to work around issues where Android reports an unexpected MIME type ([Issue #93], [PR #97])

### Version 1.29

* Avoid appending `.bin` file extension for the `application/octet-stream` MIME type ([Issue #73], [PR #74])
* Show notification when files are being uploaded in the background and try to keep process alive ([PR #75])
* Update dependencies ([PR #76])
* Minor code improvements in tests ([PR #77])

### Version 1.28

* Update rclone to 1.68.1 ([PR #71])

### Version 1.27

* Target API 35 ([PR #67], [PR #68], [PR #69])
* Update rclone to 1.68.0 ([PR #70])

### Version 1.26

* Update checksum for `tensorflow-lite-metadata-0.1.0-rc2.pom` dependency ([PR #60])
* Avoid calling Java functions from Go to prevent a panic when built with go 1.22.5 ([PR #62])
* Update all dependencies ([PR #63])
* Use Material 3 switches for switch preferences ([PR #64])
* Make dynamic shortcuts configurable and fix crash when there are too many shortcuts ([Issue #65], [PR #66])

### Version 1.25

* Update rclone to 1.67.0 ([PR #58])

### Version 1.24

* Fix race condition that sometimes causes the OAuth2 authorization dialog to not autofill the token after a successful login ([Issue #55], [PR #56])

### Version 1.23

* Fix tests after last rclone update ([PR #52])
* Update all dependencies ([PR #53])
* Add support for app shortcuts ([PR #54])
  * Long press RSAF's launcher icon to quickly open remotes in DocumentsUI

### Version 1.22

* Update rclone to 1.66.0 ([PR #51])

### Version 1.21

* Update rclone to 1.65.2 ([PR #48])
* Update Android gradle plugin to 8.2.2 ([PR #49])

### Version 1.20

* Update rclone to 1.65.1 ([PR #47])
* Normalize paths before comparison in `isChildDocument()` ([Issue #44], [PR #45])
  * Fixes compatibility with apps that directly manipulate SAF URIs
* Fix debug logging of `projection` parameter value ([PR #46])

### Version 1.18

* Update rclone to 1.65.0 ([PR #41])
* Update dependencies ([PR #42])

### Version 1.17

* Update dependencies ([PR #38])
* Disable arm64 memory tagging extensions support ([PR #39])
  * Golang's cgo runtime currently does not support MTE and would cause rclone/RSAF to crash.
  * This workaround only affects people who explicitly enable MTE for user apps on the Pixel 8 series or future ARMv9 devices.

### Version 1.16

* Update rclone to 1.64.2 ([PR #37])

### Version 1.15

* Update rclone to 1.64.1 ([PR #36])

### Version 1.14

* Update dependencies and target API 34 ([PR #34])

### Version 1.13

* Update rclone to 1.64.0 ([PR #33])

### Version 1.12

* All external app access to files on hidden remotes is now blocked ([Issue #27], [PR #31])
* Add option to lock app settings behind biometric/PIN/password unlock ([Issue #27], [PR #32])

### Version 1.11

* Add option to request "all files" permission on Android 11+ to allow wrapper remotes, like `crypt`, to access `/sdcard` ([Issue #28], [PR #29])
* Update all dependencies ([PR #30])

### Version 1.10

* Update rclone to 1.63.1 ([PR #26])
* Enable checksum validation for all gradle dependencies ([PR #25])

### Version 1.9

* Fix crash when cancelling the Edit Remote dialog on Android <=12 ([Issue #16], [PR #24])

### Version 1.8

* Fix race condition in rclone initialization that could lead to crashes when accessing files while RSAF's user interface is closed ([Issue #22], [PR #23])

### Version 1.7

* Update rclone to 1.63.0 ([PR #20])
* Reduce directory cache time from 5 minutes to 5 seconds ([PR #21])
  * After a file copy or move, the files in the target directory will no longer appear as missing for 5 minutes. Due to API limitations, there's no way to manually invalidate the cache after a copy/move operation, so a short timeout is used instead.

### Version 1.6

* Fix hang when hiding remotes that use OAuth2 authentication ([Issue #16], [PR #19])

### Version 1.5

* Add support for hiding remotes from DocumentsUI ([Issue #16], [PR #17])
* Update dependencies ([PR #18])

### Version 1.4

* Improve UX for resetting to current/default values and add support for revealing passwords in the interactive configuration dialog ([Issue #8], [PR #14])
* Add option to show all dialogs at the bottom of the screen ([Issue #9], [PR #15])

### Version 1.3

* Fix cache directory being set to non-writable `/data/local/tmp/` in certain contexts ([Issue #11], [PR #12])
* Work around upstream bug where passwords are not obscured in the config file, breaking password-based authentication (eg. smb, sftp) ([Issue #7], [PR #13])

### Version 1.2

* Update all dependencies ([PR #2], [PR #5])
* Fix `isChildDocument` returning false for nested children, which caused some apps to crash ([PR #3])

### Version 1.1

* Add option to open remotes in DocumentsUI ([PR #1])

### Version 1.0

* Initial release

<!-- Do not manually edit the lines below. Use `./gradlew changelogUpdateLinks` to regenerate. -->
[Issue #7]: https://github.com/chenxiaolong/RSAF/issues/7
[Issue #8]: https://github.com/chenxiaolong/RSAF/issues/8
[Issue #9]: https://github.com/chenxiaolong/RSAF/issues/9
[Issue #11]: https://github.com/chenxiaolong/RSAF/issues/11
[Issue #16]: https://github.com/chenxiaolong/RSAF/issues/16
[Issue #22]: https://github.com/chenxiaolong/RSAF/issues/22
[Issue #27]: https://github.com/chenxiaolong/RSAF/issues/27
[Issue #28]: https://github.com/chenxiaolong/RSAF/issues/28
[Issue #44]: https://github.com/chenxiaolong/RSAF/issues/44
[Issue #50]: https://github.com/chenxiaolong/RSAF/issues/50
[Issue #55]: https://github.com/chenxiaolong/RSAF/issues/55
[Issue #65]: https://github.com/chenxiaolong/RSAF/issues/65
[Issue #73]: https://github.com/chenxiaolong/RSAF/issues/73
[Issue #79]: https://github.com/chenxiaolong/RSAF/issues/79
[Issue #81]: https://github.com/chenxiaolong/RSAF/issues/81
[Issue #93]: https://github.com/chenxiaolong/RSAF/issues/93
[Issue #106]: https://github.com/chenxiaolong/RSAF/issues/106
[Issue #119]: https://github.com/chenxiaolong/RSAF/issues/119
[PR #1]: https://github.com/chenxiaolong/RSAF/pull/1
[PR #2]: https://github.com/chenxiaolong/RSAF/pull/2
[PR #3]: https://github.com/chenxiaolong/RSAF/pull/3
[PR #5]: https://github.com/chenxiaolong/RSAF/pull/5
[PR #12]: https://github.com/chenxiaolong/RSAF/pull/12
[PR #13]: https://github.com/chenxiaolong/RSAF/pull/13
[PR #14]: https://github.com/chenxiaolong/RSAF/pull/14
[PR #15]: https://github.com/chenxiaolong/RSAF/pull/15
[PR #17]: https://github.com/chenxiaolong/RSAF/pull/17
[PR #18]: https://github.com/chenxiaolong/RSAF/pull/18
[PR #19]: https://github.com/chenxiaolong/RSAF/pull/19
[PR #20]: https://github.com/chenxiaolong/RSAF/pull/20
[PR #21]: https://github.com/chenxiaolong/RSAF/pull/21
[PR #23]: https://github.com/chenxiaolong/RSAF/pull/23
[PR #24]: https://github.com/chenxiaolong/RSAF/pull/24
[PR #25]: https://github.com/chenxiaolong/RSAF/pull/25
[PR #26]: https://github.com/chenxiaolong/RSAF/pull/26
[PR #29]: https://github.com/chenxiaolong/RSAF/pull/29
[PR #30]: https://github.com/chenxiaolong/RSAF/pull/30
[PR #31]: https://github.com/chenxiaolong/RSAF/pull/31
[PR #32]: https://github.com/chenxiaolong/RSAF/pull/32
[PR #33]: https://github.com/chenxiaolong/RSAF/pull/33
[PR #34]: https://github.com/chenxiaolong/RSAF/pull/34
[PR #36]: https://github.com/chenxiaolong/RSAF/pull/36
[PR #37]: https://github.com/chenxiaolong/RSAF/pull/37
[PR #38]: https://github.com/chenxiaolong/RSAF/pull/38
[PR #39]: https://github.com/chenxiaolong/RSAF/pull/39
[PR #41]: https://github.com/chenxiaolong/RSAF/pull/41
[PR #42]: https://github.com/chenxiaolong/RSAF/pull/42
[PR #45]: https://github.com/chenxiaolong/RSAF/pull/45
[PR #46]: https://github.com/chenxiaolong/RSAF/pull/46
[PR #47]: https://github.com/chenxiaolong/RSAF/pull/47
[PR #48]: https://github.com/chenxiaolong/RSAF/pull/48
[PR #49]: https://github.com/chenxiaolong/RSAF/pull/49
[PR #51]: https://github.com/chenxiaolong/RSAF/pull/51
[PR #52]: https://github.com/chenxiaolong/RSAF/pull/52
[PR #53]: https://github.com/chenxiaolong/RSAF/pull/53
[PR #54]: https://github.com/chenxiaolong/RSAF/pull/54
[PR #56]: https://github.com/chenxiaolong/RSAF/pull/56
[PR #58]: https://github.com/chenxiaolong/RSAF/pull/58
[PR #60]: https://github.com/chenxiaolong/RSAF/pull/60
[PR #62]: https://github.com/chenxiaolong/RSAF/pull/62
[PR #63]: https://github.com/chenxiaolong/RSAF/pull/63
[PR #64]: https://github.com/chenxiaolong/RSAF/pull/64
[PR #66]: https://github.com/chenxiaolong/RSAF/pull/66
[PR #67]: https://github.com/chenxiaolong/RSAF/pull/67
[PR #68]: https://github.com/chenxiaolong/RSAF/pull/68
[PR #69]: https://github.com/chenxiaolong/RSAF/pull/69
[PR #70]: https://github.com/chenxiaolong/RSAF/pull/70
[PR #71]: https://github.com/chenxiaolong/RSAF/pull/71
[PR #74]: https://github.com/chenxiaolong/RSAF/pull/74
[PR #75]: https://github.com/chenxiaolong/RSAF/pull/75
[PR #76]: https://github.com/chenxiaolong/RSAF/pull/76
[PR #77]: https://github.com/chenxiaolong/RSAF/pull/77
[PR #78]: https://github.com/chenxiaolong/RSAF/pull/78
[PR #80]: https://github.com/chenxiaolong/RSAF/pull/80
[PR #82]: https://github.com/chenxiaolong/RSAF/pull/82
[PR #83]: https://github.com/chenxiaolong/RSAF/pull/83
[PR #84]: https://github.com/chenxiaolong/RSAF/pull/84
[PR #85]: https://github.com/chenxiaolong/RSAF/pull/85
[PR #86]: https://github.com/chenxiaolong/RSAF/pull/86
[PR #87]: https://github.com/chenxiaolong/RSAF/pull/87
[PR #88]: https://github.com/chenxiaolong/RSAF/pull/88
[PR #89]: https://github.com/chenxiaolong/RSAF/pull/89
[PR #90]: https://github.com/chenxiaolong/RSAF/pull/90
[PR #91]: https://github.com/chenxiaolong/RSAF/pull/91
[PR #92]: https://github.com/chenxiaolong/RSAF/pull/92
[PR #94]: https://github.com/chenxiaolong/RSAF/pull/94
[PR #95]: https://github.com/chenxiaolong/RSAF/pull/95
[PR #96]: https://github.com/chenxiaolong/RSAF/pull/96
[PR #97]: https://github.com/chenxiaolong/RSAF/pull/97
[PR #98]: https://github.com/chenxiaolong/RSAF/pull/98
[PR #99]: https://github.com/chenxiaolong/RSAF/pull/99
[PR #103]: https://github.com/chenxiaolong/RSAF/pull/103
[PR #104]: https://github.com/chenxiaolong/RSAF/pull/104
[PR #105]: https://github.com/chenxiaolong/RSAF/pull/105
[PR #107]: https://github.com/chenxiaolong/RSAF/pull/107
[PR #108]: https://github.com/chenxiaolong/RSAF/pull/108
[PR #109]: https://github.com/chenxiaolong/RSAF/pull/109
[PR #110]: https://github.com/chenxiaolong/RSAF/pull/110
[PR #111]: https://github.com/chenxiaolong/RSAF/pull/111
[PR #113]: https://github.com/chenxiaolong/RSAF/pull/113
[PR #114]: https://github.com/chenxiaolong/RSAF/pull/114
[PR #115]: https://github.com/chenxiaolong/RSAF/pull/115
[PR #116]: https://github.com/chenxiaolong/RSAF/pull/116
[PR #117]: https://github.com/chenxiaolong/RSAF/pull/117
[PR #118]: https://github.com/chenxiaolong/RSAF/pull/118
[PR #120]: https://github.com/chenxiaolong/RSAF/pull/120
[PR #121]: https://github.com/chenxiaolong/RSAF/pull/121
[PR #123]: https://github.com/chenxiaolong/RSAF/pull/123
[PR #125]: https://github.com/chenxiaolong/RSAF/pull/125
[PR #126]: https://github.com/chenxiaolong/RSAF/pull/126
[PR #129]: https://github.com/chenxiaolong/RSAF/pull/129
[PR #130]: https://github.com/chenxiaolong/RSAF/pull/130
[PR #133]: https://github.com/chenxiaolong/RSAF/pull/133
[PR #135]: https://github.com/chenxiaolong/RSAF/pull/135
[PR #136]: https://github.com/chenxiaolong/RSAF/pull/136
[PR #138]: https://github.com/chenxiaolong/RSAF/pull/138
