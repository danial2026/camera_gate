package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var frameRe = regexp.MustCompile(`^frame_(\d{8}_\d{6}\.\d{9})(_mot)?\.jpg$`)

// frameName builds a timestamp-based filename, e.g.
// frame_20260814_120405.123456789.jpg or frame_..._mot.jpg for motion frames.
func frameName(t time.Time, mot bool) string {
	n := fmt.Sprintf("frame_%s.jpg", t.Format("20060102_150405.000000000"))
	if mot {
		n = strings.TrimSuffix(n, ".jpg") + "_mot.jpg"
	}
	return n
}

func parseFrameName(name string) (time.Time, bool) {
	m := frameRe.FindStringSubmatch(filepath.Base(name))
	if m == nil {
		return time.Time{}, false
	}
	t, err := time.ParseInLocation("20060102_150405.000000000", m[1], time.Local)
	return t, err == nil
}

// SweepFrames deletes saved frames older than keep and reports what it removed.
func SweepFrames(dir string, keep time.Duration) (removed int, freedBytes int64, err error) {
	matches, err := filepath.Glob(filepath.Join(dir, "frame_*.jpg"))
	if err != nil {
		return 0, 0, err
	}
	cut := time.Now().Add(-keep)
	for _, m := range matches {
		ts, ok := parseFrameName(m)
		if !ok || !ts.Before(cut) {
			continue
		}
		if fi, e := os.Stat(m); e == nil {
			freedBytes += fi.Size()
		}
		if e := os.Remove(m); e == nil {
			removed++
		}
	}
	return removed, freedBytes, nil
}

// DirStats counts saved frames and their total size on disk.
func DirStats(dir string) (files int, bytes int64, err error) {
	matches, err := filepath.Glob(filepath.Join(dir, "frame_*.jpg"))
	if err != nil {
		return 0, 0, err
	}
	for _, m := range matches {
		if fi, e := os.Stat(m); e == nil {
			files++
			bytes += fi.Size()
		}
	}
	return files, bytes, nil
}
