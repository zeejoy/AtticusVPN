// Copyright (c) 2026 TianzeL
// SPDX-License-Identifier: Apache-2.0

// normalizeaar 把 gomobile 产出的 JAR/AAR ZIP 元数据规范化，避免构建时间和
// PowerShell/.NET 压缩实现差异改变最终哈希。压缩器来自已固定版本的 Go 工具链。
package main

import (
	"archive/zip"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

var fixedTime = time.Date(1980, 1, 1, 0, 0, 0, 0, time.UTC)

func main() {
	input := flag.String("input", "", "raw AAR input path")
	output := flag.String("output", "", "normalized AAR output path")
	flag.Parse()
	if *input == "" || *output == "" || flag.NArg() != 0 {
		fatalf("usage: normalizeaar -input raw.aar -output normalized.aar")
	}
	if err := normalize(*input, *output); err != nil {
		fatalf("normalize AAR: %v", err)
	}
}

func fatalf(format string, values ...any) {
	_, _ = fmt.Fprintf(os.Stderr, format+"\n", values...)
	os.Exit(1)
}

func normalize(input, output string) error {
	work, err := os.MkdirTemp("", "atticusvpn-normalizeaar-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(work)

	aarRoot := filepath.Join(work, "aar")
	if err := extractZip(input, aarRoot); err != nil {
		return err
	}
	classesJar := filepath.Join(aarRoot, "classes.jar")
	classesRoot := filepath.Join(work, "classes")
	if err := extractZip(classesJar, classesRoot); err != nil {
		return fmt.Errorf("extract classes.jar: %w", err)
	}
	normalizedJar := filepath.Join(work, "classes.normalized.jar")
	if err := writeZip(classesRoot, normalizedJar, nil); err != nil {
		return fmt.Errorf("normalize classes.jar: %w", err)
	}
	if err := copyFile(normalizedJar, classesJar); err != nil {
		return err
	}

	if err := os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return err
	}
	staged := output + ".tmp"
	_ = os.Remove(staged)
	if err := writeZip(aarRoot, staged, []string{"res/"}); err != nil {
		return fmt.Errorf("normalize AAR: %w", err)
	}
	_ = os.Remove(output)
	if err := os.Rename(staged, output); err != nil {
		_ = os.Remove(staged)
		return err
	}
	return nil
}

// extractZip 拒绝绝对路径与 ..，防止构建输入把文件写出自己的临时目录。
func extractZip(archivePath, destination string) error {
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		return err
	}
	defer reader.Close()
	if err := os.MkdirAll(destination, 0o755); err != nil {
		return err
	}
	root, err := filepath.Abs(destination)
	if err != nil {
		return err
	}
	rootWithSeparator := root + string(os.PathSeparator)
	for _, entry := range reader.File {
		cleanName := filepath.Clean(filepath.FromSlash(entry.Name))
		if cleanName == "." || filepath.IsAbs(cleanName) || cleanName == ".." ||
			strings.HasPrefix(cleanName, ".."+string(os.PathSeparator)) {
			return fmt.Errorf("unsafe ZIP entry %q", entry.Name)
		}
		target := filepath.Join(root, cleanName)
		absoluteTarget, err := filepath.Abs(target)
		if err != nil || (absoluteTarget != root && !strings.HasPrefix(absoluteTarget, rootWithSeparator)) {
			return fmt.Errorf("unsafe ZIP target %q", entry.Name)
		}
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(absoluteTarget, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(absoluteTarget), 0o755); err != nil {
			return err
		}
		input, err := entry.Open()
		if err != nil {
			return err
		}
		output, err := os.OpenFile(absoluteTarget, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.Copy(output, input)
		closeOutputErr := output.Close()
		closeInputErr := input.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeOutputErr != nil {
			return closeOutputErr
		}
		if closeInputErr != nil {
			return closeInputErr
		}
	}
	return nil
}

type archiveItem struct {
	name string
	path string
	dir  bool
}

// writeZip 使用固定条目顺序、时间、权限与 Go Deflate；同一固定 Go 版本下输出逐字节稳定。
func writeZip(sourceRoot, destination string, emptyEntries []string) (returnErr error) {
	items := make([]archiveItem, 0)
	for _, name := range emptyEntries {
		items = append(items, archiveItem{name: strings.TrimSuffix(name, "/") + "/", dir: true})
	}
	err := filepath.WalkDir(sourceRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		relative, err := filepath.Rel(sourceRoot, path)
		if err != nil {
			return err
		}
		items = append(items, archiveItem{name: filepath.ToSlash(relative), path: path})
		return nil
	})
	if err != nil {
		return err
	}
	sort.Slice(items, func(left, right int) bool { return items[left].name < items[right].name })

	file, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	archive := zip.NewWriter(file)
	defer func() {
		if err := archive.Close(); returnErr == nil && err != nil {
			returnErr = err
		}
		if err := file.Close(); returnErr == nil && err != nil {
			returnErr = err
		}
	}()
	for _, item := range items {
		header := &zip.FileHeader{Name: item.name, Method: zip.Deflate}
		header.SetModTime(fixedTime)
		header.SetMode(0o644)
		if item.dir {
			header.Method = zip.Store
			header.SetMode(os.ModeDir | 0o755)
		}
		writer, err := archive.CreateHeader(header)
		if err != nil {
			return err
		}
		if item.dir {
			continue
		}
		input, err := os.Open(item.path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(writer, input)
		closeErr := input.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func copyFile(source, destination string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(output, input); err != nil {
		_ = output.Close()
		return err
	}
	return output.Close()
}
