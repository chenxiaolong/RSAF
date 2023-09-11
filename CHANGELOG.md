<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0 @user] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

* All external app access to files on hidden remotes is now blocked ([Issue #27], [PR #31 @chenxiaolong])

### Version 1.11

* Add option to request "all files" permission on Android 11+ to allow wrapper remotes, like `crypt`, to access `/sdcard` ([Issue #28], [PR #29 @chenxiaolong])
* Update all dependencies ([PR #30 @chenxiaolong])

### Version 1.10

* Update rclone to 1.63.1 ([PR #26 @chenxiaolong])
* Enable checksum validation for all gradle dependencies ([PR #25 @chenxiaolong])

### Version 1.9

* Fix crash when cancelling the Edit Remote dialog on Android <=12 ([Issue #16], [PR #24 @chenxiaolong])

### Version 1.8

* Fix race condition in rclone initialization that could lead to crashes when accessing files while RSAF's user interface is closed ([Issue #22], [PR #23 @chenxiaolong])

### Version 1.7

* Update rclone to 1.63.0 ([PR #20 @chenxiaolong])
* Reduce directory cache time from 5 minutes to 5 seconds ([PR #21 @chenxiaolong])
  * After a file copy or move, the files in the target directory will no longer appear as missing for 5 minutes. Due to API limitations, there's no way to manually invalidate the cache after a copy/move operation, so a short timeout is used instead.

### Version 1.6

* Fix hang when hiding remotes that use OAuth2 authentication ([Issue #16], [PR #19 @chenxiaolong])

### Version 1.5

* Add support for hiding remotes from DocumentsUI ([Issue #16], [PR #17 @chenxiaolong])
* Update dependencies ([PR #18 @chenxiaolong])

### Version 1.4

* Improve UX for resetting to current/default values and add support for revealing passwords in the interactive configuration dialog ([Issue #8], [PR #14 @chenxiaolong])
* Add option to show all dialogs at the bottom of the screen ([Issue #9], [PR #15 @chenxiaolong])

### Version 1.3

* Fix cache directory being set to non-writable `/data/local/tmp/` in certain contexts ([Issue #11], [PR #12 @chenxiaolong])
* Work around upstream bug where passwords are not obscured in the config file, breaking password-based authentication (eg. smb, sftp) ([Issue #7], [PR #13 @chenxiaolong])

### Version 1.2

* Update all dependencies ([PR #2 @chenxiaolong], [PR #5 @chenxiaolong])
* Fix `isChildDocument` returning false for nested children, which caused some apps to crash ([PR #3 @chenxiaolong])

### Version 1.1

* Add option to open remotes in DocumentsUI ([PR #1 @chenxiaolong])

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
[PR #1 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/1
[PR #2 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/2
[PR #3 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/3
[PR #5 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/5
[PR #12 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/12
[PR #13 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/13
[PR #14 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/14
[PR #15 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/15
[PR #17 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/17
[PR #18 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/18
[PR #19 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/19
[PR #20 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/20
[PR #21 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/21
[PR #23 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/23
[PR #24 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/24
[PR #25 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/25
[PR #26 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/26
[PR #29 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/29
[PR #30 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/30
[PR #31 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/31
