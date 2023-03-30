// This is a thin wrapper around rclone's RPC calls and VFS system.
//
// The RPC calls rely on librclone, which has a stable API, but the VFS parts
// directly call rclone's internal API. It'll likely require updates when
// upgrading rclone.
//
// Variable name semantics:
//   - "section": A configuration section name, i.e. remote without the colon.
//   - "remote": Name of a remote with the trailing colon. An empty string for
//     referencing local paths is not supported.
//   - "doc": rclone-style path in the form: <remote><path>. A doc also serves
//     as the ID for SAF documents. Trailing slashes are not valid.
package rcbridge

import (
	"context"
	"io"
	ioFs "io/fs"
	"os"
	"sort"
	"strings"
	goSync "sync"
	"syscall"
	"time"

	_ "golang.org/x/mobile/event/key"

	_ "github.com/rclone/rclone/backend/all"
	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/cache"
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/fs/fspath"
	"github.com/rclone/rclone/fs/operations"
	"github.com/rclone/rclone/fs/sync"
	"github.com/rclone/rclone/lib/oauthutil"
	"github.com/rclone/rclone/librclone/librclone"
	"github.com/rclone/rclone/vfs"
	"github.com/rclone/rclone/vfs/vfscommon"
)

var (
	vfsLock  goSync.Mutex
	vfsCache = make(map[string]*vfs.VFS)
	errMap   = map[error]syscall.Errno{
		vfs.ENOTEMPTY:                  syscall.ENOTEMPTY,
		vfs.ESPIPE:                     syscall.ESPIPE,
		vfs.EBADF:                      syscall.EBADF,
		vfs.EROFS:                      syscall.EROFS,
		vfs.ENOSYS:                     syscall.ENOSYS,
		vfs.ENOENT:                     syscall.ENOENT,
		vfs.EEXIST:                     syscall.EEXIST,
		vfs.EPERM:                      syscall.EPERM,
		vfs.EINVAL:                     syscall.EINVAL,
		vfs.ECLOSED:                    syscall.EBADF,
		fs.ErrorDirExists:              syscall.EEXIST,
		fs.ErrorDirNotFound:            syscall.ENOENT,
		fs.ErrorObjectNotFound:         syscall.ENOENT,
		fs.ErrorIsFile:                 syscall.ENOTDIR,
		fs.ErrorIsDir:                  syscall.EISDIR,
		fs.ErrorDirectoryNotEmpty:      syscall.ENOTEMPTY,
		fs.ErrorPermissionDenied:       syscall.EACCES,
		fs.ErrorFileNameTooLong:        syscall.ENAMETOOLONG,
		config.ErrorConfigFileNotFound: syscall.ENOENT,
	}
)

// Initialize global aspects of the library.
func RbInit(cacheDir string) {
	librclone.Initialize()
	config.SetCacheDir(cacheDir)

	// Don't allow interactive password prompts
	ci := fs.GetConfig(context.Background())
	ci.AskPassword = false
}

// Clean up library resources.
//
// Note that this is a best-effort operation and it is impossible to completely
// unload the golang runtime and other things associated with rclone.
func RbFinalize() {
	librclone.Finalize()
}

// Set the global logging verbosity for rclone.
func RbSetLogVerbosity(verbosity int) {
	ci := fs.GetConfig(context.Background())

	if verbosity >= 2 {
		ci.LogLevel = fs.LogLevelDebug
	} else if verbosity == 1 {
		ci.LogLevel = fs.LogLevelInfo
	} else {
		ci.LogLevel = fs.LogLevelNotice
	}
}

type RbRpcResult struct {
	Output string
	Status int
}

// Perform an rclone RPC call.
//
// The input should be a serialized JSON object. The output string is also a
// serialized JSON object. The status code is an HTTP status code.
func RbRpcCall(method string, input string) *RbRpcResult {
	output, status := librclone.RPC(method, input)

	return &RbRpcResult{
		Output: output,
		Status: status,
	}
}

func RbVersion() string {
	// fs.VersionSuffix is a hardcoded value, so just ignore it
	return fs.VersionTag
}

type RbError struct {
	Msg  string
	Code int
}

// Update errOut with the specified error, translating to the nearest errno
// equivalent if possible. If the error can't be mapped to an errno value, the
// error code is set to the specified fallback.
func assignError(errOut *RbError, err error, fallback syscall.Errno) {
	if errOut != nil {
		errOut.Msg = err.Error()

		errno, ok := err.(syscall.Errno)
		if ok {
			errOut.Code = int(errno)
			return
		}

		errno, ok = errMap[err]
		if ok {
			errOut.Code = int(errno)
			return
		}

		errOut.Code = int(fallback)
	}
}

// Clear fs and vfs instances associated with the specified remote. The vfs
// instance, if any, will be shut down with a short interval to wait for pending
// writes.
func RbCacheClearRemote(remote string) {
	vfsLock.Lock()
	defer vfsLock.Unlock()

	v, ok := vfsCache[remote]
	if ok {
		v.WaitForWriters(1 * time.Minute)
		v.CleanUp()
		v.Shutdown()
		delete(vfsCache, remote)
	}

	cache.ClearConfig(remote)
}

// Clear cached fs and vfs instances. All vfs instances will be shut down with a
// short interval to wait for pending writes.
func RbCacheClearAll() {
	vfsLock.Lock()
	defer vfsLock.Unlock()

	for k := range vfsCache {
		vfsCache[k].WaitForWriters(1 * time.Minute)
		vfsCache[k].CleanUp()
		vfsCache[k].Shutdown()
		delete(vfsCache, k)
	}

	cache.Clear()
}

func RbConfigCheckName(name string, errOut *RbError) bool {
	err := fspath.CheckConfigName(name)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	return true
}

func RbConfigCopySection(oldName string, newName string) {
	for _, key := range config.Data().GetKeyList(oldName) {
		value, found := config.Data().GetValue(oldName, key)
		if found {
			config.Data().SetValue(newName, key, value)
		}
	}
}

func RbConfigSetPath(path string, errOut *RbError) bool {
	err := config.SetConfigPath(path)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

func RbConfigSetPassword(password string, errOut *RbError) bool {
	err := config.SetConfigPassword(password)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

func RbConfigClearPassword() {
	config.ClearConfigPassword()
}

func RbConfigLoad(errOut *RbError) bool {
	// We explicitly call this instead of config.LoadedData() so that errors can
	// be reported
	err := config.Data().Load()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	RbCacheClearAll()

	return true
}

func RbConfigSave(errOut *RbError) bool {
	// We explicitly call this instead of config.SaveConfig() so that errors can
	// be reported
	err := config.Data().Save()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Create an fs instance or get it from the cache if it exists. The path can
// point to the root of the remote or a subdirectory. If it points to a file,
// then the fs for the parent directory is returned along with the
// fs.ErrorIsFile error.
func getFs(remote string) (fs.Fs, error) {
	return cache.Get(context.Background(), remote)
}

// Create an fs that points to the specified document if it is a directory (or
// does not exist). If the document is a file, then return an fs that points to
// the parent directory, along with the filename of the file. This behavior is
// the same as cmd.NewFsFile(), except errors are returned instead of making the
// process exit.
//
// Note that this intentionally does not cache the fs instance because unless it
// points to a root, file operations may invalidate it (eg. a directory is
// deleted and a file is created in its place).
func getFsForDoc(doc string) (fs.Fs, string, error) {
	_, name, err := fspath.Split(doc)
	if err != nil {
		return nil, "", err
	}

	f, err := fs.NewFs(context.Background(), doc)
	if err == fs.ErrorIsFile {
		return f, name, nil
	} else if err != nil {
		return nil, "", err
	}

	return f, "", nil
}

// Create a vfs instance for the given remote. The path can point to the root
// of the remote or a subdirectory. The vfs is configured to allow caching
// writes to disk in order to allow opening files for both reading and writing
// at the same time.
func getVfs(remote string) (*vfs.VFS, error) {
	f, err := getFs(remote)
	if err != nil {
		return nil, err
	}

	vfsLock.Lock()
	defer vfsLock.Unlock()

	v, ok := vfsCache[remote]
	if !ok {
		opts := vfscommon.DefaultOpt
		// This is required for O_RDWR
		opts.CacheMode = vfscommon.CacheModeWrites
		// Don't let the cache grow too big
		opts.CacheMaxAge = 60 * time.Second
		// Adjust read buffering to be more appropriate for a mobile app.
		// Maybe make this configurable in the future.
		opts.ChunkSize = 2 * fs.Mebi
		opts.ChunkSizeLimit = 8 * fs.Mebi
		// Synchronously upload on file close
		opts.WriteBack = 0

		v = vfs.New(f, &opts)
		vfsCache[remote] = v
	}

	return v, nil
}

// Create a vfs instance for the given document or get it from the cache if it
// already exists. The vfs is created from the root of the document's remote.
// Returns the vfs and the document's path within the remote.
func getVfsForDoc(doc string) (*vfs.VFS, string, error) {
	remote, path, err := fspath.SplitFs(doc)
	if err != nil {
		return nil, "", err
	}

	v, err := getVfs(remote)
	if err != nil {
		return nil, "", err
	}

	return v, path, nil
}

type RbPathSplitResult struct {
	ParentDoc string
	LeafName  string
}

// Split a document into the parent directory and leaf elements.
func RbPathSplit(doc string, errOut *RbError) *RbPathSplitResult {
	parentDoc, leafName, err := fspath.Split(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	// Trim the trailing slash to ensure that it remains a valid document ID
	// (matches what listing the grandparent's contents would return)
	parentDoc = strings.TrimRight(parentDoc, "/")

	return &RbPathSplitResult{
		ParentDoc: parentDoc,
		LeafName:  leafName,
	}
}

// Join a parent directory document with a leaf filename.
func RbPathJoin(parentDoc string, leafName string) string {
	return fspath.JoinRootPath(parentDoc, leafName)
}

// Like vfs.ReadDir(), except it fails with ENOTDIR if the path does not point
// to a directory.
func readDir(vfs *vfs.VFS, path string) ([]os.FileInfo, error) {
	f, err := vfs.Open(path)
	if err != nil {
		return nil, err
	} else if !f.Node().IsDir() {
		return nil, syscall.ENOTDIR
	}

	fis, err := f.Readdir(-1)
	closeErr := f.Close()

	if err != nil {
		return nil, err
	} else if closeErr != nil {
		return nil, closeErr
	}

	sort.Slice(fis, func(i, j int) bool { return fis[i].Name() < fis[j].Name() })
	return fis, nil
}

type RbDirEntry struct {
	Doc     string
	Name    string
	Size    int64
	Mode    int
	ModTime int64
}

type RbDirEntryReceiver interface {
	Process(entry *RbDirEntry) bool
}

func fileModeToStatMode(mode os.FileMode) (result int) {
	switch mode & os.ModeType {
	case os.ModeDir:
		result = syscall.S_IFDIR
	case os.ModeSymlink:
		result = syscall.S_IFLNK
	case os.ModeNamedPipe:
		result = syscall.S_IFIFO
	case os.ModeSocket:
		result = syscall.S_IFSOCK
	case os.ModeDevice:
		result = syscall.S_IFBLK
	case os.ModeCharDevice:
		result = syscall.S_IFCHR
	default:
		result = syscall.S_IFREG
	}

	result |= int(mode & os.ModePerm)

	return result
}

func newDirEntry(fi os.FileInfo, doc string, docIsParent bool) RbDirEntry {
	entryDoc := doc
	if docIsParent {
		entryDoc = fspath.JoinRootPath(doc, fi.Name())
	}

	return RbDirEntry{
		Doc:     entryDoc,
		Name:    fi.Name(),
		Size:    fi.Size(),
		Mode:    fileModeToStatMode(fi.Mode()),
		ModTime: fi.ModTime().UnixMilli(),
	}
}

// Iterate through the contents of a directory. The directory entries are
// retrieved all at once before the receiver is invoked. The entries are sorted
// lexicographically by name.
func RbDocIterDir(doc string, errOut *RbError, receiver RbDirEntryReceiver) bool {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	fis, err := readDir(v, path)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	for _, fi := range fis {
		entry := newDirEntry(fi, doc, true)
		if !receiver.Process(&entry) {
			break
		}
	}

	return true
}

// Stat a single document without following symlinks.
func RbDocStat(doc string, errOut *RbError) *RbDirEntry {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	fi, err := v.Stat(path)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return nil
	}

	entry := newDirEntry(fi, doc, false)
	return &entry
}

// Create a directory with the specified permissions.
func RbDocMkdir(doc string, perms int, errOut *RbError) bool {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	err = v.Mkdir(path, ioFs.FileMode(perms&int(ioFs.ModePerm)))
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Rename a document. On failure, the error code may be a generic EIO, even if
// it could potentially be described by more meaningful codes. This is due to
// heavy use of custom (string) errors. Aside from EEXIST, errors cannot be
// relied on for making decisions (eg. for TOCTOU avoidance).
func RbDocRename(sourceDoc string, targetDoc string, errOut *RbError) bool {
	sourceVfs, sourcePath, err := getVfsForDoc(sourceDoc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}
	targetVfs, targetPath, err := getVfsForDoc(targetDoc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	if sourceVfs != targetVfs {
		assignError(errOut, syscall.EINVAL, syscall.EINVAL)
		return false
	}

	err = sourceVfs.Rename(sourcePath, targetPath)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Delete a document (optionally recursively).
func RbDocRemove(doc string, recurse bool, errOut *RbError) bool {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	node, err := v.Stat(path)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	operation := node.Remove
	if recurse {
		operation = node.RemoveAll
	}

	err = operation()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Copy or move a document. If the target exists, its type (directory or not)
// much match the type of the source. If the documents are directories, then the
// contents are copied/moved. In other words, the source directory's name is not
// added as a path element in the target. If a target file already exists, it
// will be overwritten.
//
// This uses server-side copying/moving if it's supported by the remote backend.
// Otherwise, it falls back to downloading and reuploading the data.
func RbDocCopyOrMove(sourceDoc string, targetDoc string, copy bool, errOut *RbError) bool {
	// If a document exists and is a file, then fs points to its parent
	// directory and the filename is the document's filename. Otherwise, the fs
	// points to the document directly and the filename is empty. This means we
	// can't distinguish between a document being a directory or not existing.
	// We'll just follow the behavior of rclone's copyto/moveto in assuming that
	// the source document exists and use filename == "" to decide which code
	// path to take.
	sourceFs, sourceFile, err := getFsForDoc(sourceDoc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}
	targetFs, targetFile, err := getFsForDoc(targetDoc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	var opErr error
	ctx := context.Background()

	if sourceFile == "" {
		if targetFile != "" {
			// We need to explicitly check if the target is a file since CopyDir
			// and MoveDir are only aware of sourceFs, which is the parent
			// directory in this scenario.
			opErr = fs.ErrorIsFile
		} else if copy {
			opErr = sync.CopyDir(ctx, targetFs, sourceFs, true)
		} else {
			opErr = sync.MoveDir(ctx, targetFs, sourceFs, true, true)

			// Even though deleteEmptySrcDirs is set to true, MoveDir() fails to
			// delete the leftover empty source directory when performing a move
			// where the target directory already exists.
			if opErr == nil {
				sourceFs.Rmdir(ctx, "")
			}
		}
	} else {
		sourceObj, err := sourceFs.NewObject(ctx, sourceFile)
		if err != nil {
			assignError(errOut, err, syscall.EIO)
			return false
		}

		targetObj, err := targetFs.NewObject(ctx, targetFile)
		if err == fs.ErrorObjectNotFound {
			targetObj = nil
		} else if err != nil {
			assignError(errOut, err, syscall.EIO)
			return false
		}

		operation := operations.Move
		if copy {
			operation = operations.Copy
		}

		_, opErr = operation(ctx, targetFs, targetObj, targetFile, sourceObj)
	}

	if opErr != nil {
		assignError(errOut, opErr, syscall.EIO)
		return false
	}

	return true
}

type RbFile struct {
	file vfs.Handle
}

// Open a file in the VFS at the given path. This works like POSIX open().
func RbDocOpen(doc string, flags int, mode int, errOut *RbError) *RbFile {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	handle, err := v.OpenFile(path, flags, ioFs.FileMode(mode&int(ioFs.ModePerm)))
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return nil
	}

	return &RbFile{
		file: handle,
	}
}

// Close the file handle. This works like POSIX close().
//
// Even if an error is returned, the file handle should be considered closed.
func (rbfile *RbFile) Close(errOut *RbError) bool {
	err := rbfile.file.Close()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Read from the file handle at the specified offset. This works like Linux's
// pread().
//
// On success, the number of bytes read is returned. When EOF is reached, the
// number of bytes read may be less than the size requested. If an error occurs,
// then -1 is returned.
func (rbfile *RbFile) ReadAt(data []byte, size int, offset int64, errOut *RbError) int {
	n, err := rbfile.file.ReadAt(data[:size], offset)
	if err != nil && err != io.EOF {
		assignError(errOut, err, syscall.EIO)
		return -1
	}

	return n
}

// Write to the file handle at the specified offset. This works like Linux's
// pwrite().
//
// On success, the number of bytes written is returned. If the disk is full and
// EOF is reached, the number of bytes written may be less than the size
// requested. If an error occurs, then -1 is returned.
func (rbfile *RbFile) WriteAt(data []byte, size int, offset int64, errOut *RbError) int {
	n, err := rbfile.file.WriteAt(data[:size], offset)
	if err != nil && err != io.EOF {
		assignError(errOut, err, syscall.EIO)
		return -1
	}

	return n
}

// Flush the file handle. This works like POSIX fsync(), including the fact that
// the backend may totally ignore it.
func (rbfile *RbFile) Flush(errOut *RbError) bool {
	err := rbfile.file.Flush()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

// Stat the file and get the file size. Returns -1 if an error occurs.
func (rbfile *RbFile) GetSize(errOut *RbError) int64 {
	fi, err := rbfile.file.Stat()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return -1
	}

	return fi.Size()
}

// The internal API for `rclone authorize` is not suitable for library usage.
// None of the underlying functions are available outside of the oauthutil
// package and the higher level functions print to stdout and can only be killed
// by sending a bad request to it. But since that's what we have to work with,
// we'll deal with it on the Android side by parsing logcat.
func RbAuthorize(argsNullSep string, errOut *RbError) bool {
	var args = strings.Split(argsNullSep, "\x00")

	err := config.Authorize(context.Background(), args, true, "")
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	return true
}

func RbAuthorizeUrl() string {
	return oauthutil.RedirectLocalhostURL
}
