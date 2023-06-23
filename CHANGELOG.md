<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0 @user] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

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
