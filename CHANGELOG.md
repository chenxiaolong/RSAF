<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

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
[Issue #55]: https://github.com/chenxiaolong/RSAF/issues/55
[Issue #65]: https://github.com/chenxiaolong/RSAF/issues/65
[Issue #73]: https://github.com/chenxiaolong/RSAF/issues/73
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
