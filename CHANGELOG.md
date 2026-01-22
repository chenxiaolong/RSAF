<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

**2025-10-02 Update: RSAF will _not_ be participating in Google's developer verification program ([more details](https://github.com/chenxiaolong/RSAF/issues/189)). This will soon impact your ability to install RSAF on most Android devices.**

### Unreleased

* Update AGP to 9.0.0 ([PR #222])

### Version 3.27

* Update rclone to 1.72.1 ([PR #218])
* Resume interrupted uploads (caused by a crash) after an app update, not just when the device reboots ([PR #219])

### Version 3.26

* Add support for showing more detailed error messages and copying them ([Issue #209], [PR #212])
* Fix crash when translating unhashable error types to errno error codes ([Issue #211], [PR #213])

### Version 3.25

* Fix dynamic shortcuts not updating immediately after importing a config ([PR #206])
* Fix deadlock when uploading files with the mega backend ([Issue #200], [PR #207])
    * (This explanation was updated post release. [PR #208]) This was a regression introduced in RSAF 3.1. Due to a bug in how RSAF integrates with rclone for loading TLS certificates, HTTP requests could only be made serially. The mega backend opens a long-running connection to listen for events, which prevented all further HTTP requests from being sent.

### Version 3.24

* Add support for "allow local storage access" option in Android <=10 ([Issue #164], [PR #204])

### Version 3.23

* Update rclone to 1.72.0 ([PR #201])
* Use the same `User-Agent` header as upstream rclone ([PR #202])
* Fix missing rclone info and debug logs when debugging options are enabled ([PR #203])
    * This was a regression introduced with the rclone 1.70.0 upgrade in RSAF 3.9.

### Version 3.22

* Work around Android bug where `system_server` crashes when file close operations take too long ([Issue #157], [PR #197])

### Version 3.21

* Make message text selectable when configuring a remote ([Issue #193], [PR #194])

### Version 3.20

* Add support for setting arbitrary rclone VFS options ([Issue #190], [PR #192])

### Version 3.19

* Update rclone to 1.71.2 ([PR #191])

### Version 3.18

* Update rclone to 1.71.1 ([PR #186])

### Version 3.17

* Temporarily downgrade AGP to 8.11.1 for compatibility with F-Droid's build server ([Issue #50], [Issue #185])

### Version 3.16

* Fix installation of missing Android SDK components when running `./gradlew rcbridge` directly ([Issue #50], [PR #182])
* Fix missing property exception when running `./gradlew tasks` ([PR #183])
* Update dependencies ([PR #184])

### Version 3.15

* Add PNG icon for F-Droid metadata ([Issue #50], [PR #180])
* Remove dependency info block from APK ([Issue #50], [PR #181])

### Version 3.14

* Add version code to `metadata/version.txt` in the repo instead of in APK filename ([Issue #50], [PR #176])
* Make builds more reproducible ([Issue #50], [PR #175], [PR #177], [PR #178])

### Version 3.13

* Update rclone to 1.71.0 ([PR #171])

### Version 3.12

* Update rclone to 1.70.3 ([PR #166])

### Version 3.11

* Update rclone to 1.70.2 ([PR #165])

### Version 3.10

* Update rclone to 1.70.1 ([PR #163])

### Version 3.9

* Update rclone to 1.70.0, update dependencies, and target API 36 ([PR #162])

### Version 3.8

* Add support for the `findDocumentPath` SAF call ([Issue #158], [PR #160])
* Update dependencies ([PR #161])

### Version 3.7

* Add new per-remote option for disabling thumbnail support ([Issue #155], [PR #156])

### Version 3.6

* Update rclone to 1.69.3 and update other dependencies too ([PR #154])

### Version 3.5

* Update rclone to 1.69.2 and update other dependencies too ([PR #150])

### Version 3.4

* Use local pinned version of gomobile during build ([PR #135])
* Add the machine-readable version code to the APK filename ([Issue #50], [PR #136])
* Make gomobile shared library reproducible ([PR #138])
* Add support for `mlkem768x25519-sha256` key exchange algorithm for sftp backend ([PR #143])
  * This is the new post-quantum key exchange algorithm that's used by default on OpenSSH 10.0 servers.
* Update all dependencies ([PR #144])
* Remove deprecated androidx security-crypto library dependency ([PR #145])

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
[Issue #155]: https://github.com/chenxiaolong/RSAF/issues/155
[Issue #157]: https://github.com/chenxiaolong/RSAF/issues/157
[Issue #158]: https://github.com/chenxiaolong/RSAF/issues/158
[Issue #164]: https://github.com/chenxiaolong/RSAF/issues/164
[Issue #185]: https://github.com/chenxiaolong/RSAF/issues/185
[Issue #190]: https://github.com/chenxiaolong/RSAF/issues/190
[Issue #193]: https://github.com/chenxiaolong/RSAF/issues/193
[Issue #200]: https://github.com/chenxiaolong/RSAF/issues/200
[Issue #209]: https://github.com/chenxiaolong/RSAF/issues/209
[Issue #211]: https://github.com/chenxiaolong/RSAF/issues/211
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
[PR #143]: https://github.com/chenxiaolong/RSAF/pull/143
[PR #144]: https://github.com/chenxiaolong/RSAF/pull/144
[PR #145]: https://github.com/chenxiaolong/RSAF/pull/145
[PR #150]: https://github.com/chenxiaolong/RSAF/pull/150
[PR #154]: https://github.com/chenxiaolong/RSAF/pull/154
[PR #156]: https://github.com/chenxiaolong/RSAF/pull/156
[PR #160]: https://github.com/chenxiaolong/RSAF/pull/160
[PR #161]: https://github.com/chenxiaolong/RSAF/pull/161
[PR #162]: https://github.com/chenxiaolong/RSAF/pull/162
[PR #163]: https://github.com/chenxiaolong/RSAF/pull/163
[PR #165]: https://github.com/chenxiaolong/RSAF/pull/165
[PR #166]: https://github.com/chenxiaolong/RSAF/pull/166
[PR #171]: https://github.com/chenxiaolong/RSAF/pull/171
[PR #175]: https://github.com/chenxiaolong/RSAF/pull/175
[PR #176]: https://github.com/chenxiaolong/RSAF/pull/176
[PR #177]: https://github.com/chenxiaolong/RSAF/pull/177
[PR #178]: https://github.com/chenxiaolong/RSAF/pull/178
[PR #180]: https://github.com/chenxiaolong/RSAF/pull/180
[PR #181]: https://github.com/chenxiaolong/RSAF/pull/181
[PR #182]: https://github.com/chenxiaolong/RSAF/pull/182
[PR #183]: https://github.com/chenxiaolong/RSAF/pull/183
[PR #184]: https://github.com/chenxiaolong/RSAF/pull/184
[PR #186]: https://github.com/chenxiaolong/RSAF/pull/186
[PR #191]: https://github.com/chenxiaolong/RSAF/pull/191
[PR #192]: https://github.com/chenxiaolong/RSAF/pull/192
[PR #194]: https://github.com/chenxiaolong/RSAF/pull/194
[PR #197]: https://github.com/chenxiaolong/RSAF/pull/197
[PR #201]: https://github.com/chenxiaolong/RSAF/pull/201
[PR #202]: https://github.com/chenxiaolong/RSAF/pull/202
[PR #203]: https://github.com/chenxiaolong/RSAF/pull/203
[PR #204]: https://github.com/chenxiaolong/RSAF/pull/204
[PR #206]: https://github.com/chenxiaolong/RSAF/pull/206
[PR #207]: https://github.com/chenxiaolong/RSAF/pull/207
[PR #208]: https://github.com/chenxiaolong/RSAF/pull/208
[PR #212]: https://github.com/chenxiaolong/RSAF/pull/212
[PR #213]: https://github.com/chenxiaolong/RSAF/pull/213
[PR #218]: https://github.com/chenxiaolong/RSAF/pull/218
[PR #219]: https://github.com/chenxiaolong/RSAF/pull/219
[PR #222]: https://github.com/chenxiaolong/RSAF/pull/222
