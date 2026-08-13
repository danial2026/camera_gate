package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"unicode/utf8"
)

// retOption is one entry of the retention picker. hours <= 0 = keep all.
type retOption struct {
	label  string
	hours  float64
	custom bool
}

var retOptions = []retOption{
	{"1 hour", 1, false},
	{"12 hours", 12, false},
	{"24 hours", 24, false},
	{"7 days", 168, false},
	{"Custom hours...", 0, true},
	{"Keep everything", -1, false},
}

// main-form row ids
const (
	rowURL = iota
	rowToken
	rowOut
	rowRetention
	rowTransport
	rowGUI
	rowDash
	rowEvery
	rowStart
	rowQuit
	rowCount
)

const (
	menuRetention = iota
	menuTransport
)

// special keys (split from plain runes via negative codes)
const (
	kUp    = -1
	kDown  = -2
	kLeft  = -3
	kRight = -4
	kEnter = -5
	kEsc   = -6
	kBack  = -7
	kDel   = -8
	kHome  = -9
	kEnd   = -10
)

const tuiW = 64

var rawSaved string

type wizard struct {
	cfg *Config

	sel     int
	edit    bool
	rowEdit int
	custom  bool // editing a custom-retention hours value
	buf     []rune
	pos     int

	menu     bool
	menuKind int
	menuIdx  int
	menuOpts []string

	retIdx int
}

func (w *wizard) matchRetIdx() {
	for i, o := range retOptions {
		if !o.custom && o.hours == w.cfg.RetentionHours {
			w.retIdx = i
			return
		}
	}
	if w.cfg.RetentionHours <= 0 {
		w.retIdx = len(retOptions) - 1
	} else {
		w.retIdx = 4
	}
}

func (w *wizard) retentionLabel() string {
	o := retOptions[w.retIdx]
	if o.custom {
		if w.cfg.RetentionHours > 0 {
			return "Custom (" + humanHours(w.cfg.RetentionHours) + ")"
		}
		return "Custom hours..."
	}
	return o.label
}

func (w *wizard) transportLabel() string {
	if w.cfg.Direct {
		return "raw"
	}
	return "curl"
}

// runWizard shows the interactive setup screen. Returns start/quit.
// Falls back to line prompts when raw terminal mode is unavailable.
func runWizard(cfg *Config) (bool, error) {
	w := &wizard{cfg: cfg}
	w.matchRetIdx()
	if err := enterRawMode(); err != nil {
		return runWizardFallback(cfg)
	}
	defer exitRawMode()
	w.draw()
	r := bufio.NewReaderSize(os.Stdin, 64)
	for {
		k, err := readKey(r)
		if err != nil {
			break
		}
		if k == 3 { // ctrl-c (raw mode swallows SIGINT)
			k = kEsc
		}
		redraw, start, quit := w.handle(k)
		if redraw {
			if w.menu {
				w.drawMenu()
			} else {
				w.draw()
			}
		}
		if start {
			restoreTerm()
			return true, nil
		}
		if quit {
			restoreTerm()
			return false, nil
		}
	}
	restoreTerm()
	return false, nil
}

func (w *wizard) handle(k int) (redraw, start, quit bool) {
	if w.menu {
		switch k {
		case kUp, 'k', 'K':
			w.menuIdx = (w.menuIdx + len(w.menuOpts) - 1) % len(w.menuOpts)
		case kDown, 'j', 'J':
			w.menuIdx = (w.menuIdx + 1) % len(w.menuOpts)
		case kEnter:
			return w.menuPick()
		case kEsc, kLeft:
			w.menu = false
		default:
			if k >= '1' && k <= '9' {
				i := k - '1'
				if int(i) < len(w.menuOpts) {
					w.menuIdx = int(i)
					return w.menuPick()
				}
			}
		}
		return true, false, false
	}
	if w.edit {
		switch k {
		case kEnter:
			w.commitEdit()
		case kEsc, kUp, kDown:
			w.commitEdit()
			if k == kUp {
				w.sel = max(0, w.sel-1)
			} else if k == kDown {
				w.sel = min(rowCount-1, w.sel+1)
			}
		case kHome:
			w.pos = 0
		case kEnd:
			w.pos = len(w.buf)
		case kLeft:
			if w.pos > 0 {
				w.pos--
			}
		case kRight:
			if w.pos < len(w.buf) {
				w.pos++
			}
		case kBack:
			if w.pos > 0 {
				w.pos--
				w.buf = append(w.buf[:w.pos], w.buf[w.pos+1:]...)
			}
		case kDel:
			if w.pos < len(w.buf) {
				w.buf = append(w.buf[:w.pos], w.buf[w.pos+1:]...)
			}
		default:
			if k > 0 && utf8.ValidRune(rune(k)) {
				if w.custom && !(k >= '0' && k <= '9' || k == '.' || k == ',') {
					break
				}
				w.buf = append(w.buf, 0)
				copy(w.buf[w.pos+1:], w.buf[w.pos:])
				w.buf[w.pos] = rune(k)
				w.pos++
			}
		}
		return true, false, false
	}
	switch {
	case k == kUp || k == 'k' || k == 'K':
		w.sel = max(0, w.sel-1)
	case k == kDown || k == 'j' || k == 'J':
		w.sel = min(rowCount-1, w.sel+1)
	case k == kEnter || k == kRight || k == ' ':
		switch w.sel {
		case rowURL, rowToken, rowOut, rowEvery:
			w.beginEdit()
		case rowRetention:
			w.beginRetentionMenu()
		case rowTransport:
			w.cfg.Direct = !w.cfg.Direct
		case rowGUI:
			w.cfg.GUI = !w.cfg.GUI
		case rowDash:
			w.cfg.Dashboard = !w.cfg.Dashboard
		case rowStart:
			return true, true, false
		case rowQuit:
			return true, false, true
		}
	case k == kEsc:
		return true, false, true
	}
	return true, false, false
}

func (w *wizard) beginEdit() {
	w.edit = true
	w.rowEdit = w.sel
	w.custom = false
	switch w.sel {
	case rowURL:
		w.buf = []rune(w.cfg.URL)
	case rowToken:
		w.buf = []rune(w.cfg.Token)
	case rowOut:
		w.buf = []rune(w.cfg.OutDir)
	case rowEvery:
		w.buf = []rune(strconv.Itoa(w.cfg.SaveEvery))
	}
	w.pos = len(w.buf)
}

func (w *wizard) commitEdit() {
	if w.custom {
		txt := strings.TrimSpace(string(w.buf))
		txt = strings.ReplaceAll(txt, ",", ".")
		if h, err := strconv.ParseFloat(txt, 64); err == nil && h > 0 {
			w.cfg.RetentionHours = h
			w.retIdx = 4
		}
		w.custom = false
		w.edit = false
		return
	}
	switch w.rowEdit {
	case rowURL:
		w.cfg.URL = normalizeURL(string(w.buf))
	case rowToken:
		w.cfg.Token = strings.TrimSpace(string(w.buf))
	case rowOut:
		w.cfg.OutDir = strings.TrimSpace(string(w.buf))
	case rowEvery:
		if n, err := strconv.Atoi(strings.TrimSpace(string(w.buf))); err == nil && n >= 1 {
			w.cfg.SaveEvery = n
		}
	}
	w.edit = false
}

func (w *wizard) beginRetentionMenu() {
	w.menu = true
	w.menuKind = menuRetention
	w.menuOpts = make([]string, len(retOptions))
	for i, o := range retOptions {
		w.menuOpts[i] = o.label
	}
	w.menuIdx = w.retIdx
}

func (w *wizard) menuPick() (redraw, start, quit bool) {
	switch w.menuKind {
	case menuRetention:
		o := retOptions[w.menuIdx]
		if o.custom {
			w.menu = false
			w.edit = true
			w.custom = true
			w.rowEdit = rowRetention
			if w.cfg.RetentionHours > 0 {
				w.buf = []rune(strconv.FormatFloat(w.cfg.RetentionHours, 'f', -1, 64))
			} else {
				w.buf = nil
			}
			w.pos = len(w.buf)
			return true, false, false
		}
		w.cfg.RetentionHours = o.hours
		w.retIdx = w.menuIdx
		w.menu = false
	}
	return true, false, false
}

// ------------------------------------------------------------------ drawing

func (w *wizard) draw() {
	var sb strings.Builder
	sb.WriteString("\x1b[2J\x1b[H")
	inner := tuiW - 2
	sb.WriteString("┌" + strings.Repeat("─", inner) + "┐\n")
	sb.WriteString("│" + centerStr("CameraGate viewer setup", inner) + "│\n")
	sb.WriteString("├" + strings.Repeat("─", inner) + "┤\n")
	for _, line := range w.rowLines(inner) {
		sb.WriteString("│" + padRight(line, inner) + "│\n")
	}
	sb.WriteString("├" + strings.Repeat("─", inner) + "┤\n")
	sb.WriteString("│" + centerStr("↑/↓ or j/k move · Enter edit · Esc quit", inner) + "│\n")
	sb.WriteString("└" + strings.Repeat("─", inner) + "┘\n")
	os.Stdout.WriteString(sb.String())
}

func (w *wizard) rowLines(inner int) []string {
	var lines []string
	cursor := func(idx int) string {
		if w.sel == idx {
			return ">"
		}
		return " "
	}
	value := func(v string, idx int) string {
		if w.edit && w.rowEdit == idx {
			pre := string(w.buf[:w.pos])
			post := string(w.buf[w.pos:])
			return pre + "▍" + post
		}
		return v
	}
	add := func(idx int, name, v string) {
		lbl := padRight(name+":", 22)
		val := value(v, idx)
		val = truncate(val, inner-29)
		lines = append(lines, " "+cursor(idx)+" "+lbl+"["+val+"]")
	}

	add(rowURL, "Camera URL", w.cfg.URL)
	add(rowToken, "Auth token", maskToken(w.cfg.Token))
	add(rowOut, "Frame directory", w.cfg.OutDir)
	add(rowRetention, "Retention", w.retentionLabel())
	add(rowTransport, "Transport", w.transportLabel())
	add(rowGUI, "GUI viewer", onOff(w.cfg.GUI))
	add(rowDash, "Terminal dashboard", onOff(w.cfg.Dashboard))
	add(rowEvery, "Save every Nth frame", strconv.Itoa(w.cfg.SaveEvery))

	toggle := " off"
	if w.cfg.MotionThreshold > 0 {
		toggle = " on (threshold " + strconv.FormatFloat(w.cfg.MotionThreshold, 'g', 2, 64) + ")"
	}
	add(rowCount, "Motion detection", toggle)

	lines = append(lines, "")
	lines = append(lines, "   "+cursor(rowStart)+" ▶ Save & Start")
	lines = append(lines, "   "+cursor(rowQuit)+" ✕ Quit")
	return lines
}

func (w *wizard) drawMenu() {
	var sb strings.Builder
	sb.WriteString("\x1b[2J\x1b[H")
	inner := tuiW - 2
	sb.WriteString("┌" + strings.Repeat("─", inner) + "┐\n")
	title := "Select retention period"
	if w.menuKind == menuTransport {
		title = "Select transport"
	}
	sb.WriteString("│" + centerStr(title, inner) + "│\n")
	sb.WriteString("├" + strings.Repeat("─", inner) + "┤\n")
	for i, o := range w.menuOpts {
		mark := "   "
		if i == w.menuIdx {
			mark = " > "
		}
		line := " " + mark + o
		if o == "Custom hours..." {
			line += " (e.g. 2.5)"
		}
		sb.WriteString("│" + padRight(line, inner) + "│\n")
	}
	sb.WriteString("├" + strings.Repeat("─", inner) + "┤\n")
	sb.WriteString("│" + centerStr("↑/↓ choose · Enter confirm · Esc back", inner) + "│\n")
	sb.WriteString("└" + strings.Repeat("─", inner) + "┘\n")
	os.Stdout.WriteString(sb.String())
}

// ------------------------------------------------------------------- keys

func readKey(r *bufio.Reader) (int, error) {
	b, err := r.ReadByte()
	if err != nil {
		return 0, err
	}
	switch b {
	case '\r', '\n':
		return kEnter, nil
	case 0x1b:
		b2, err := r.ReadByte()
		if err != nil {
			return kEsc, nil
		}
		if b2 == '[' {
			b3, err := r.ReadByte()
			if err != nil {
				return kEsc, nil
			}
			switch b3 {
			case 'A':
				return kUp, nil
			case 'B':
				return kDown, nil
			case 'C':
				return kRight, nil
			case 'D':
				return kLeft, nil
			case 'H':
				return kHome, nil
			case 'F':
				return kEnd, nil
			case '3':
				r.ReadByte() // consume '~'
				return kDel, nil
			}
			return kEsc, nil
		}
		return kEsc, nil
	case 0x7f, 0x08:
		return kBack, nil
	case 0x03:
		return 3, nil
	}
	if b < 0x80 {
		return int(b), nil
	}
	size := 1
	switch {
	case b&0xe0 == 0xc0:
		size = 2
	case b&0xf0 == 0xe0:
		size = 3
	case b&0xf8 == 0xf0:
		size = 4
	}
	buf := make([]byte, size)
	buf[0] = b
	for i := 1; i < size; i++ {
		if c, err := r.ReadByte(); err == nil {
			buf[i] = c
		}
	}
	rr, _ := utf8.DecodeRune(buf)
	return int(rr), nil
}

// ------------------------------------------------------------------ term

func enterRawMode() error {
	save := exec.Command("stty", "-g")
	save.Stdin = os.Stdin
	out, err := save.Output()
	if err != nil {
		return err
	}
	rawSaved = strings.TrimSpace(string(out))
	raw := exec.Command("stty", "raw", "-echo")
	raw.Stdin = os.Stdin
	if err := raw.Run(); err != nil {
		return err
	}
	os.Stdout.WriteString("\x1b[2J\x1b[?25l\x1b[H")
	return nil
}

func restoreTerm() {
	os.Stdout.WriteString("\x1b[?25h\x1b[2J\x1b[H")
}

func exitRawMode() {
	restoreTerm()
	if rawSaved != "" {
		restore := exec.Command("stty", rawSaved)
		restore.Stdin = os.Stdin
		restore.Run()
	}
}

// ------------------------------------------------------------ fallback TUI

// runWizardFallback is used when raw terminal mode is unavailable: a plain
// line-based questionnaire with the same options.
func runWizardFallback(cfg *Config) (bool, error) {
	fmt.Println("CameraGate viewer setup (non-interactive terminal)")
	r := bufio.NewReaderSize(os.Stdin, 128)
	prompt := func(name, cur string) string {
		fmt.Printf("  %-28s [%s]: ", name, cur)
		line, _ := r.ReadString('\n')
		if v := strings.TrimSpace(line); v != "" {
			return v
		}
		return cur
	}
	cfg.URL = normalizeURL(prompt("Camera URL", cfg.URL))
	cfg.Token = prompt("Auth token (optional)", cfg.Token)
	cfg.OutDir = prompt("Frame directory", cfg.OutDir)
	ret := prompt("Retention (keep/1h/12h/24h/7d/2.5h)", cfg.RetentionLabel())
	if h, ok := parseRetention(ret); ok {
		cfg.RetentionHours = h
	}
	yn := func(name string, cur bool) bool {
		fmt.Printf("  %-28s [%v] (y/n): ", name, cur)
		line, _ := r.ReadString('\n')
		ls := strings.ToLower(strings.TrimSpace(line))
		if strings.HasPrefix(ls, "y") {
			return true
		}
		if strings.HasPrefix(ls, "n") {
			return false
		}
		return cur
	}
	cfg.Direct = yn("Use raw dialer (not curl)", cfg.Direct)
	cfg.GUI = yn("Show GUI viewer", cfg.GUI)
	cfg.Dashboard = yn("Terminal dashboard", cfg.Dashboard)
	if n, err := strconv.Atoi(prompt("Save every Nth frame", strconv.Itoa(cfg.SaveEvery))); err == nil && n >= 1 {
		cfg.SaveEvery = n
	}
	fmt.Println()
	return true, nil
}

// ------------------------------------------------------------- helpers

func onOff(b bool) string {
	if b {
		return "on"
	}
	return "off"
}

func maskToken(t string) string {
	if t == "" {
		return ""
	}
	if len(t) <= 6 {
		return strings.Repeat("•", len(t))
	}
	return "••••••" + t[len(t)-2:]
}

func padRight(s string, n int) string {
	if len(s) >= n {
		return s
	}
	return s + strings.Repeat(" ", n-len(s))
}

func centerStr(s string, w int) string {
	if len(s) >= w {
		return s[:w]
	}
	l := (w - len(s)) / 2
	return strings.Repeat(" ", l) + s + strings.Repeat(" ", w-len(s)-l)
}

func truncate(s string, w int) string {
	if len(s) <= w {
		return s
	}
	if w <= 1 {
		return s[:w]
	}
	return s[:w-1] + "…"
}
