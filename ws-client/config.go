package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Config is the persisted viewer setup. RetentionHours <= 0 means
// "keep everything".
type Config struct {
	URL             string  `json:"url"`
	Token           string  `json:"token"`
	OutDir          string  `json:"out_dir"`
	RetentionHours  float64 `json:"retention_hours"`
	Direct          bool    `json:"direct"`
	SaveEvery       int     `json:"save_every"`
	GUI             bool    `json:"gui"`
	Dashboard       bool    `json:"dashboard"`
	MotionThreshold float64 `json:"motion_threshold"`
}

func DefaultConfig() Config {
	return Config{
		URL:             "ws://192.168.0.1:8080/ws",
		RetentionHours:  24,
		SaveEvery:       1,
		GUI:             true,
		MotionThreshold: 6,
	}
}

func DefaultConfigPath() string {
	if d, err := os.UserConfigDir(); err == nil {
		return filepath.Join(d, "cameragate", "viewer.json")
	}
	return "viewer.json"
}

func LoadConfig(path string) Config {
	c := DefaultConfig()
	b, err := os.ReadFile(path)
	if err != nil {
		return c
	}
	_ = json.Unmarshal(b, &c)
	if c.SaveEvery < 1 {
		c.SaveEvery = 1
	}
	return c
}

func (c Config) Save(path string) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, b, 0o600)
}

// KeepFor returns how long saved frames are kept; 0 means keep everything.
func (c Config) KeepFor() time.Duration {
	if c.RetentionHours <= 0 {
		return 0
	}
	return time.Duration(c.RetentionHours * float64(time.Hour))
}

func (c Config) RetentionLabel() string {
	for _, o := range retOptions {
		if !o.custom && o.hours == c.RetentionHours {
			return o.label
		}
	}
	if c.RetentionHours <= 0 {
		return "keep everything"
	}
	return "custom (" + humanHours(c.RetentionHours) + ")"
}

func humanHours(h float64) string {
	if h == float64(int64(h)) {
		n := int64(h)
		if n >= 24 && n%24 == 0 {
			return strconv.FormatInt(n/24, 10) + " days"
		}
		return strconv.FormatInt(n, 10) + " h"
	}
	return strconv.FormatFloat(h, 'g', 3, 64) + " h"
}

// parseRetention accepts "1h", "12h", "24h", "168h", "2.5h", "7d", "keep".
// It returns -1 for "keep everything" (or 0/keep/all/forever).
func parseRetention(s string) (float64, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	switch s {
	case "", "keep", "all", "forever", "0":
		return -1, true
	}
	return parseHours(s)
}

func parseHours(s string) (float64, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	if d := strings.TrimSuffix(s, "d"); d != s {
		if n, err := strconv.ParseFloat(d, 64); err == nil {
			return n * 24, true
		}
		return 0, false
	}
	if h := strings.TrimSuffix(s, "h"); h != s {
		if n, err := strconv.ParseFloat(h, 64); err == nil {
			return n, true
		}
		return 0, false
	}
	n, err := strconv.ParseFloat(s, 64)
	return n, err == nil
}

// normalizeURL adds the ws:// scheme when missing.
func normalizeURL(u string) string {
	u = strings.TrimSpace(u)
	if u == "" {
		return u
	}
	if !strings.HasPrefix(u, "ws://") && !strings.HasPrefix(u, "wss://") {
		u = "ws://" + u
	}
	return u
}
