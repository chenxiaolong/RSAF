// SPDX-FileCopyrightText: 2025 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

// `gomobile bind` generates a go.mod file with a `replace` directive that
// points to an absolute path. This shows up in the .go.buildinfo ELF section
// even if building with -trimpath. This program is an ugly wrapper around `go`
// to prevent `go list -json ...` from returning absolute paths to gomobile in
// the first place.

package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
)

func removeString(haystack []string, needle string) ([]string, bool) {
	for i, item := range haystack {
		if item == needle {
			return append(haystack[:i], haystack[i+1:]...), true
		}
	}

	return haystack, false
}

func findGo() (string, error) {
	self, err := os.Executable()
	if err != nil {
		return "", fmt.Errorf("failed to find self: %v: %w", os.Args[0], err)
	}

	statSelf, err := os.Stat(self)
	if err != nil {
		return "", fmt.Errorf("failed to stat self: %v: %w", self, err)
	}

	var pathDirs []string

	for _, dir := range filepath.SplitList(os.Getenv("PATH")) {
		pathDirs = append(pathDirs, filepath.Clean(dir))
	}

	for len(pathDirs) != 0 {
		candidate, err := exec.LookPath("go")
		if err != nil {
			return "", err
		}

		statCandidate, err := os.Stat(candidate)
		if err != nil {
			return "", fmt.Errorf("failed to stat candidate: %v", candidate)
		}

		if !os.SameFile(statSelf, statCandidate) {
			return candidate, nil
		}

		parent := filepath.Clean(filepath.Dir(candidate))
		pathDirs, removed := removeString(pathDirs, parent)

		if !removed {
			return "", fmt.Errorf("failed to remove %v from PATH: %v", parent, pathDirs)
		}

		os.Setenv("PATH", strings.Join(pathDirs, string(filepath.ListSeparator)))
	}

	return "", errors.New("original go executable not found in PATH")
}

func replaceAbsPaths(basePath string, data map[string]any) error {
	relDir, err := filepath.Rel(basePath, data["Dir"].(string))
	if err != nil {
		return fmt.Errorf("failed to compute relative path: %v: %w", data["Dir"], err)
	}

	relGoMod, err := filepath.Rel(basePath, data["GoMod"].(string))
	if err != nil {
		return fmt.Errorf("failed to compute relative path: %v: %w", data["GoMod"], err)
	}

	data["Dir"] = relDir
	data["GoMod"] = relGoMod

	return nil
}

func run() (int, error) {
	args := os.Args[1:]

	goExecutable, err := findGo()
	if err != nil {
		return -1, err
	}

	cmd := exec.Command(goExecutable, args...)
	cmd.Stderr = os.Stderr

	if slices.Contains(args, "list") && slices.Contains(args, "-json") {
		basePath := os.Getenv("GOWRAPPER_BASE_PATH")
		if basePath == "" {
			return -1, errors.New("GOWRAPPER_BASE_PATH is unset")
		}

		output, err := cmd.Output()
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				return ee.ExitCode(), nil
			}

			return -1, err
		}

		decoder := json.NewDecoder(bytes.NewReader(output))

		for {
			var data map[string]interface{}

			err := decoder.Decode(&data)
			if err != nil {
				if err == io.EOF {
					break
				}

				return -1, fmt.Errorf("failed to decode JSON: %v: %w", string(output), err)
			}

			if _, ok := data["Main"]; ok {
				if err = replaceAbsPaths(basePath, data); err != nil {
					return -1, err
				}
			} else if replaceRaw, ok := data["Replace"]; ok {
				if replace, ok := replaceRaw.(map[string]any); ok {
					if _, ok = replace["Path"]; ok {
						if err = replaceAbsPaths(basePath, replace); err != nil {
							return -1, err
						}
					}
				}
			}

			encoded, err := json.MarshalIndent(data, "", "\t")
			if err != nil {
				return -1, fmt.Errorf("failed to encode to JSON: %v: %w", data, err)
			}

			fmt.Println(string(encoded))
		}
	} else {
		cmd.Stdout = os.Stdout

		if err := cmd.Run(); err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				return ee.ExitCode(), nil
			}

			return -1, err
		}
	}

	return 0, nil
}

func main() {
	exitCode, err := run()
	if err != nil {
		log.Fatal(err)
	}

	os.Exit(exitCode)
}
