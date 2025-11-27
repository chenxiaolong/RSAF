// SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

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
	// This package's init() MUST run first
	_ "rcbridge/envhack"

	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"io"
	ioFs "io/fs"
	"os"
	"sort"
	"strconv"
	"strings"
	goSync "sync"
	"syscall"
	"time"

	_ "golang.org/x/mobile/event/key"

	_ "github.com/rclone/rclone/backend/all"
	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/cache"
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/fs/config/configstruct"
	"github.com/rclone/rclone/fs/config/obscure"
	"github.com/rclone/rclone/fs/fshttp"
	"github.com/rclone/rclone/fs/fspath"
	"github.com/rclone/rclone/fs/operations"
	"github.com/rclone/rclone/fs/sync"
	"github.com/rclone/rclone/lib/oauthutil"
	"github.com/rclone/rclone/librclone/librclone"
	"github.com/rclone/rclone/vfs"
	"github.com/rclone/rclone/vfs/vfscommon"
)

const (
	rsafLegacyVfsCaching = "rsaf:vfs_caching"
	rsafVfsPrefix        = "rsaf:vfs:"
)

var (
	vfsLock          goSync.Mutex
	vfsInstances     = make(map[string]*vfs.VFS)
	vfsOptValidKeys  = make(map[string]bool)
	vfsOptStringKeys = make(map[string]bool)
	caCertsLock      goSync.Mutex
	caCertsPool      *x509.CertPool
)

func init() {
	items, err := configstruct.Items(&vfscommon.Opt)
	if err != nil {
		// Can't fail.
		panic(err)
	}

	for _, item := range items {
		if item.Name == "vfs_write_back" {
			// This is never configurable by the user and does not have a static
			// value due to the workaround described in getVfsOpts(). Don't
			// report the value nor allow it to be set.
			continue
		}

		vfsOptValidKeys[item.Name] = true

		switch item.Value.(type) {
		case string:
			vfsOptStringKeys[item.Name] = true
		}
	}
}

// Load as many certificates from the PEM data as possible and return the last
// error, if any.
func parsePemCerts(data []byte) (certs []*x509.Certificate, err error) {
	for len(data) > 0 {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" || len(block.Headers) != 0 {
			continue
		}

		certBytes := block.Bytes
		cert, err := x509.ParseCertificate(certBytes)
		if err != nil {
			continue
		}

		certs = append(certs, cert)
	}

	return certs, err
}

// Generate a trust store pool that we can pass to rclone via the per-request
// hook we add in our rclone fork. This is necessary because golang currently
// does not support reading from the proper Android directories. We can't just
// set SSL_CERT_DIR either, even with envhack, because the user CA directory
// contains DER-encoded certificates and golang only supports loading PEM.
//
// Additionally, our implementation will not trust any system CA certificates
// that the user explicitly disabled from Android's settings.
//
// https://github.com/golang/go/issues/71258
func generateTrustStorePool() *x509.CertPool {
	systemDir := os.Getenv("ANDROID_ROOT")
	dataDir := os.Getenv("ANDROID_DATA")

	// This has never changed since 2011 when support for multi-user was added.
	androidUid := os.Getuid() / 100_000

	addDirs := []string{
		"/apex/com.android.conscrypt/cacerts",
		systemDir + "/etc/security/cacerts",
		fmt.Sprintf("%s/misc/user/%d/cacerts-added", dataDir, androidUid),
	}
	removeDirs := []string{
		fmt.Sprintf("%s/misc/user/%d/cacerts-removed", dataDir, androidUid),
	}

	// Map from the certificate hash to the certificate path.
	caFiles := make(map[string]string)

	// Add all available certificates.
	for _, dir := range addDirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			fs.Logf(nil, "Failed to read directory: %v: %v", dir, err)
			continue
		}

		for _, entry := range entries {
			if !entry.Type().IsRegular() {
				continue
			}

			name := entry.Name()

			_, ok := caFiles[name]
			if ok {
				continue
			}

			caFiles[name] = dir + "/" + name
		}
	}

	// And then remove all certificates disabled by the user.
	for _, dir := range removeDirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			fs.Logf(nil, "Failed to read directory: %v: %v", dir, err)
			continue
		}

		for _, entry := range entries {
			if !entry.Type().IsRegular() {
				continue
			}

			delete(caFiles, entry.Name())
		}
	}

	pool := x509.NewCertPool()

	for _, path := range caFiles {
		data, err := os.ReadFile(path)
		if err != nil {
			fs.Logf(nil, "Failed to read file: %v: %v", path, err)
			continue
		}

		certs, err := x509.ParseCertificates(data)
		if err != nil {
			certs, err = parsePemCerts(data)
		}
		if err != nil {
			fs.Logf(nil, "Failed to load certs: %v: %v", path, err)
			continue
		}

		for _, cert := range certs {
			pool.AddCert(cert)
		}
	}

	fs.Logf(nil, "Loaded %d certificates", len(caFiles))

	return pool
}

// Set the trusted CA certificates on every HTTP request.
func perRequestHook(config *tls.Config) {
	caCertsLock.Lock()
	defer caCertsLock.Unlock()

	config.RootCAs = caCertsPool
}

// Initialize global aspects of the library.
func RbInit() {
	librclone.Initialize()

	ci := fs.GetConfig(context.Background())

	// Don't allow interactive password prompts.
	ci.AskPassword = false

	// Use the same user agent header as rclone. Without this, the default user
	// agent is just "rclone/".
	ci.UserAgent = fmt.Sprintf("rclone/%s", fs.VersionTag)

	fshttp.SetRoundTripHook(perRequestHook)
}

// Reload certificates from the system and user trust stores.
func RbReloadCerts() {
	caCertsLock.Lock()
	defer caCertsLock.Unlock()

	caCertsPool = generateTrustStorePool()
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

	fs.LogReload(ci)
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

		switch err {
		case vfs.ENOTEMPTY:
			errOut.Code = int(syscall.ENOTEMPTY)
		case vfs.ESPIPE:
			errOut.Code = int(syscall.ESPIPE)
		case vfs.EBADF:
			errOut.Code = int(syscall.EBADF)
		case vfs.EROFS:
			errOut.Code = int(syscall.EROFS)
		case vfs.ENOSYS:
			errOut.Code = int(syscall.ENOSYS)
		case vfs.ELOOP:
			errOut.Code = int(syscall.ELOOP)
		case vfs.ENOENT:
			errOut.Code = int(syscall.ENOENT)
		case vfs.EEXIST:
			errOut.Code = int(syscall.EEXIST)
		case vfs.EPERM:
			errOut.Code = int(syscall.EPERM)
		case vfs.EINVAL:
			errOut.Code = int(syscall.EINVAL)
		case vfs.ECLOSED:
			errOut.Code = int(syscall.EBADF)
		case fs.ErrorDirExists:
			errOut.Code = int(syscall.EEXIST)
		case fs.ErrorDirNotFound:
			errOut.Code = int(syscall.ENOENT)
		case fs.ErrorObjectNotFound:
			errOut.Code = int(syscall.ENOENT)
		case fs.ErrorIsFile:
			errOut.Code = int(syscall.ENOTDIR)
		case fs.ErrorIsDir:
			errOut.Code = int(syscall.EISDIR)
		case fs.ErrorDirectoryNotEmpty:
			errOut.Code = int(syscall.ENOTEMPTY)
		case fs.ErrorPermissionDenied:
			errOut.Code = int(syscall.EACCES)
		case fs.ErrorNotImplemented:
			errOut.Code = int(syscall.ENOSYS)
		case fs.ErrorCommandNotFound:
			errOut.Code = int(syscall.ENOENT)
		case fs.ErrorFileNameTooLong:
			errOut.Code = int(syscall.ENAMETOOLONG)
		case config.ErrorConfigFileNotFound:
			errOut.Code = int(syscall.ENOENT)
		default:
			errOut.Code = int(fallback)
		}
	}
}

// Clear fs and vfs instances associated with the specified remote. The vfs
// instances, if any, will be shut down immediately.
func RbCacheClearRemote(remote string, deleteCacheDir bool) {
	vfsLock.Lock()
	defer vfsLock.Unlock()

	v, ok := vfsInstances[remote]
	if ok {
		fs.Logf(remote, "Removing from VFS cache")
		v.Shutdown()

		if deleteCacheDir {
			fs.Logf(remote, "Deleting VFS cache directory")
			v.CleanUp()
		}

		delete(vfsInstances, remote)
	}

	parsed, err := fspath.Parse(remote)
	if err == nil {
		cache.ClearConfig(parsed.Name)
	}
}

// Clear cached fs and vfs instances. All vfs instances will be shut down
// immediately.
func RbCacheClearAll(deleteCacheDir bool) {
	vfsLock.Lock()
	defer vfsLock.Unlock()

	for k, vfs := range vfsInstances {
		fs.Logf(k, "Removing from VFS cache")
		vfs.Shutdown()

		if deleteCacheDir {
			fs.Logf(k, "Deleting VFS cache directory")
			vfs.CleanUp()
		}

		delete(vfsInstances, k)
	}

	cache.Clear()
}

// The number of seconds to wait before all VFS instances with caching enabled
// have begun one cleanup cycle if all of their timers were to start now.
func RbCacheCleanupMaxWaitSeconds() int64 {
	vfsLock.Lock()
	defer vfsLock.Unlock()

	maxWait := fs.Duration(0)

	for _, vfs := range vfsInstances {
		if vfs.Opt.CacheMode != vfscommon.CacheModeOff {
			maxWait = max(maxWait, vfs.Opt.CacheMaxAge+vfs.Opt.CachePollInterval)
		}
	}

	return int64(maxWait / fs.Duration(time.Second))
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

func RbConfigDeleteSectionKey(name string, key string) bool {
	return config.Data().DeleteKey(name, key)
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

func RbConfigLoad(deleteCacheDir bool, errOut *RbError) bool {
	// We explicitly call this instead of config.LoadedData() so that errors can
	// be reported
	err := config.Data().Load()
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return false
	}

	// Migrate the legacy VFS caching option to the new custom VFS options map.
	for _, section := range config.Data().GetSectionList() {
		value, found := config.Data().GetValue(section, rsafLegacyVfsCaching)
		if found {
			isCaching, err := strconv.ParseBool(value)
			if err != nil {
				assignError(errOut, err, syscall.EINVAL)
				return false
			}

			var vfsCacheMode vfscommon.CacheMode
			if isCaching {
				vfsCacheMode = vfscommon.CacheModeWrites
			} else {
				vfsCacheMode = vfscommon.CacheModeOff
			}

			config.Data().SetValue(section, rsafVfsPrefix+"vfs_cache_mode", vfsCacheMode.String())
			config.Data().DeleteKey(section, rsafLegacyVfsCaching)
		}
	}

	RbCacheClearAll(deleteCacheDir)

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

type RbPasswordRevealResult struct {
	PlainText string
}

func RbPasswordReveal(obscured string, errOut *RbError) *RbPasswordRevealResult {
	plainText, err := obscure.Reveal(obscured)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	return &RbPasswordRevealResult{
		PlainText: plainText,
	}
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
// If `treatAsFile` is true, then the document is assumed to be a file.
//
// Note that this intentionally does not cache the fs instance because unless it
// points to a root, file operations may invalidate it (eg. a directory is
// deleted and a file is created in its place).
func getFsForDoc(doc string, treatAsFile bool) (fs.Fs, string, error) {
	parent, name, err := fspath.Split(doc)
	if err != nil {
		return nil, "", err
	}

	remote := doc
	if treatAsFile {
		if name == "" {
			return nil, "", fs.ErrorIsDir
		} else if parent == "" {
			parent = "."
		}
		remote = parent
	}

	f, err := fs.NewFs(context.Background(), remote)
	if !treatAsFile && err == fs.ErrorIsFile {
		return f, name, nil
	} else if err != nil {
		return nil, "", err
	}

	if treatAsFile {
		return f, name, nil
	} else {
		return f, "", nil
	}
}

type vfsOverrides map[string]string

func (c vfsOverrides) Get(key string) (value string, ok bool) {
	value, ok = c[key]
	return value, ok
}

func getVfsOverrides(remote string) (vfsOverrides, error) {
	overrides := vfsOverrides{}

	parsed, err := fspath.Parse(remote)
	if err != nil {
		return nil, err
	}

	for _, key := range config.Data().GetKeyList(parsed.Name) {
		vfsKey, matches := strings.CutPrefix(key, rsafVfsPrefix)
		if !matches {
			continue
		}

		vfsValue, found := config.Data().GetValue(parsed.Name, key)
		if !found {
			continue
		}

		overrides[vfsKey] = vfsValue
	}

	return overrides, nil
}

func getVfsOpts(overrides vfsOverrides) (vfscommon.Options, error) {
	opts := vfscommon.Opt

	// Significantly shorten the time that directory entries are cached so that
	// file listings are more likely to reflect reality after external file
	// operations made outside of rclone or local operations that touch the
	// backend directly, like copying/moving. There's no public nor internal API
	// for just invalidating the directory cache. The RC API has vfs/refresh but
	// that forces an unnecessary reread of directories.
	opts.DirCacheTime = fs.Duration(5 * time.Second)

	// Required for O_RDWR.
	opts.CacheMode = vfscommon.CacheModeWrites

	// Clean up cached files as soon as possible. The VFS cache monitor service
	// will stop as soon as all files are closed and Android will SIGSTOP the
	// process soon after that. The default poll interval generally causes files
	// to not be cleaned up until the next time the user actively interacts with
	// RSAF. If RSAF gets restarted in the meantime, the cleanup won't even run
	// until the next time the VFS is created for this specific remote.
	opts.CacheMaxAge = fs.Duration(15 * time.Second)
	opts.CachePollInterval = fs.Duration(20 * time.Second)

	// Adjust read buffering to be more appropriate for a mobile app.
	opts.ChunkSize = 2 * fs.Mebi
	opts.ChunkSizeLimit = 8 * fs.Mebi

	// Override our defaults with the custom options the user has configured.
	err := configstruct.Set(overrides, &opts)
	if err != nil {
		return opts, err
	}

	// configstruct silently ignores empty values, but we want to ensure the
	// user knows that the value is aware that the option is doing nothing.
	for key, value := range overrides {
		if !vfsOptValidKeys[key] {
			return opts, fmt.Errorf("invalid VFS option: %q", key)
		} else if len(value) == 0 && !vfsOptStringKeys[key] {
			return opts, fmt.Errorf("cannot have an empty value: %q", key)
		}
	}

	// This is initially asynchronous so that vfs.New() -> vfs.Item.reload()
	// does not block if there are dirty items in the VFS cache. This is the
	// one option that cannot be overridden. getVfs() will reset this back to
	// 0 after the VFS is initialized.
	opts.WriteBack = fs.Duration(1 * time.Millisecond)

	return opts, nil
}

type RbVfsOpt struct {
	Key   string
	Value string
}

type RbVfsOptList struct {
	items []RbVfsOpt
}

func (list *RbVfsOptList) Add(item *RbVfsOpt) {
	list.items = append(list.items, *item)
}

func (list *RbVfsOptList) Get(index int) *RbVfsOpt {
	return &list.items[index]
}

func (list *RbVfsOptList) Size() int {
	return len(list.items)
}

func RbVfsGetOpts(overrides *RbVfsOptList, errOut *RbError) *RbVfsOptList {
	overridesMap := vfsOverrides{}

	for _, override := range overrides.items {
		overridesMap[override.Key] = override.Value
	}

	opts, err := getVfsOpts(overridesMap)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	items, err := configstruct.Items(&opts)
	result := []RbVfsOpt{}

	for _, item := range items {
		if !vfsOptValidKeys[item.Name] {
			continue
		}

		value, err := configstruct.InterfaceToString(item.Value)
		if err != nil {
			assignError(errOut, err, syscall.EINVAL)
			return nil
		}

		result = append(result, RbVfsOpt{
			Key:   item.Name,
			Value: value,
		})
	}

	return &RbVfsOptList{
		items: result,
	}
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

	v, ok := vfsInstances[remote]
	if !ok {
		fs.Logf(remote, "Creating new VFS instance")

		overrides, err := getVfsOverrides(remote)
		if err != nil {
			return nil, err
		}

		opts, err := getVfsOpts(overrides)
		if err != nil {
			fs.Logf(remote, "Failed to apply VFS options overrides: %+v", overrides)
			return nil, err
		}

		v = vfs.New(f, &opts)
		vfsInstances[remote] = v

		// Make Close() synchronous again because we rely on this for the in-use
		// file tracker in RcloneProvider and also for upload error reporting.
		v.Opt.WriteBack = 0
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

type RbRemoteFeaturesResult struct {
	Copy  bool
	Move  bool
	About bool
}

// Return supported features about the specified remote.
func RbRemoteFeatures(remote string, errOut *RbError) *RbRemoteFeaturesResult {
	f, err := getFs(remote)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	features := f.Features()

	result := RbRemoteFeaturesResult{
		Copy:  features.Copy != nil,
		Move:  features.Move != nil,
		About: features.About != nil,
	}

	return &result
}

type RbRemoteSplitResult struct {
	Remote string
	Path   string
}

func RbRemoteSplit(doc string, errOut *RbError) *RbRemoteSplitResult {
	remote, path, err := fspath.SplitFs(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	return &RbRemoteSplitResult{
		Remote: remote,
		Path:   path,
	}
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

// Initialize the VFS for the specified document.
func RbDocVfsInit(doc string, errOut *RbError) bool {
	_, _, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	return true
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

type RbDirEntryList struct {
	items []RbDirEntry
}

func (list *RbDirEntryList) Get(index int) *RbDirEntry {
	return &list.items[index]
}

func (list *RbDirEntryList) Size() int {
	return len(list.items)
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

// List the contents of a directory. The entries are sorted lexicographically by
// name.
func RbDocListDir(doc string, errOut *RbError) *RbDirEntryList {
	v, path, err := getVfsForDoc(doc)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return nil
	}

	fis, err := readDir(v, path)
	if err != nil {
		assignError(errOut, err, syscall.EIO)
		return nil
	}

	entries := []RbDirEntry{}

	for _, fi := range fis {
		entry := newDirEntry(fi, doc, true)
		entries = append(entries, entry)
	}

	return &RbDirEntryList{items: entries}
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
	sourceFs, sourceFile, err := getFsForDoc(sourceDoc, false)
	if err != nil {
		assignError(errOut, err, syscall.EINVAL)
		return false
	}

	// If the source is a file, we want avoid rclone's behavior described above
	// and make targetFs point to the parent and targetFile to the filename.
	targetFs, targetFile, err := getFsForDoc(targetDoc, sourceFile != "")
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

	if v.Opt.CacheMode < vfscommon.CacheModeWrites && flags&(os.O_WRONLY|os.O_RDWR) != 0 {
		fs.Logf(nil, "Forcing O_TRUNC for writable file due to streaming")
		flags |= os.O_TRUNC
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
