<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0 @user] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

* Fix cache directory being set to non-writable `/data/local/tmp/` in certain contexts ([Issue #11], [PR #12 @chenxiaolong])

### Version 1.2

* Update all dependencies ([PR #2 @chenxiaolong], [PR #5 @chenxiaolong])
* Fix `isChildDocument` returning false for nested children, which caused some apps to crash ([PR #3 @chenxiaolong])

### Version 1.1

* Add option to open remotes in DocumentsUI ([PR #1 @chenxiaolong])

### Version 1.0

* Initial release

<!-- Do not manually edit the lines below. Use `./gradlew changelogUpdateLinks` to regenerate. -->
[Issue #11]: https://github.com/chenxiaolong/RSAF/issues/11
[PR #1 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/1
[PR #2 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/2
[PR #3 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/3
[PR #5 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/5
[PR #12 @chenxiaolong]: https://github.com/chenxiaolong/RSAF/pull/12
