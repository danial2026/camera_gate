package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"flag"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
)

// streamConn is either curl's stdout (proven-most-reliable transport for the
// phone's wifi) or a raw Go TCP connection performing the ws upgrade by hand.
type streamConn struct {
	r io.Reader
	c *exec.Cmd
	n net.Conn
}

func dialCurl(urlStr, token string) (*streamConn, error) {
	httpURL := strings.Replace(urlStr, "ws://", "http://", 1)
	httpURL = strings.Replace(httpURL, "wss://", "https://", 1)
	if token != "" {
		u, err := url.Parse(httpURL)
		if err != nil {
			return nil, err
		}
		q := u.Query()
		q.Set("token", token)
		u.RawQuery = q.Encode()
		httpURL = u.String()
	}
	cmd := exec.Command("curl", "-s", "-N",
		"-H", "Connection: Upgrade",
		"-H", "Upgrade: websocket",
		"-H", "Sec-WebSocket-Version: 13",
		"-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
		httpURL)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	br := bufio.NewReaderSize(stdout, 1<<16)
	go func() {
		if err := cmd.Wait(); err != nil {
			log.Printf("curl exited: %v", err)
		}
	}()
	return &streamConn{r: br, c: cmd}, nil
}

func dialRaw(urlStr, token string) (*streamConn, error) {
	u, err := url.Parse(urlStr)
	if err != nil {
		return nil, err
	}
	host := u.Hostname()
	if host == "" {
		host = "127.0.0.1"
	}
	port := u.Port()
	if port == "" {
		port = "80"
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, port), 5*time.Second)
	if err != nil {
		return nil, err
	}
	key := make([]byte, 16)
	rand.Read(key)
	keyStr := base64.StdEncoding.EncodeToString(key)
	q := u.Query()
	if token != "" {
		q.Set("token", token)
	}
	u.RawQuery = q.Encode()
	hostHeader := u.Host
	if hostHeader == "" {
		hostHeader = net.JoinHostPort(host, port)
	}
	req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\n"+
		"Upgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n",
		u.RequestURI(), hostHeader, keyStr)
	if _, err := conn.Write([]byte(req)); err != nil {
		conn.Close()
		return nil, err
	}
	r := bufio.NewReader(conn)
	status, err := r.ReadString('\n')
	if err != nil {
		conn.Close()
		return nil, err
	}
	if !bytes.Contains([]byte(status), []byte(" 101 ")) {
		conn.Close()
		return nil, fmt.Errorf("bad handshake: %s", status)
	}
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			conn.Close()
			return nil, err
		}
		if len(bytes.TrimSpace([]byte(line))) == 0 {
			break
		}
	}
	return &streamConn{r: r, n: conn}, nil
}

func closeConn(c *streamConn) {
	if c.n != nil {
		c.n.Close()
	}
	if c.c != nil {
		c.c.Process.Kill()
	}
}

// nextFrame reads one binary websocket frame payload (FIN + opcode 0x2, with
// masks and extended 126/127 lengths tolerated via skip). Returns nil on
// stream end.
func nextFrame(r *bufio.Reader) ([]byte, error) {
	for {
		b0, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		if b0 != 0x82 { // FIN + binary
			continue
		}
		b1, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		ln := int64(b1 & 0x7f)
		switch ln {
		case 126:
			var b [2]byte
			if _, err := io.ReadFull(r, b[:]); err != nil {
				return nil, err
			}
			ln = int64(b[0])<<8 | int64(b[1])
		case 127:
			var b [8]byte
			if _, err := io.ReadFull(r, b[:]); err != nil {
				return nil, err
			}
			ln = 0
			for i := 0; i < 8; i++ {
				ln = ln<<8 | int64(b[i])
			}
		}
		if ln <= 0 || ln > 1<<24 {
			return nil, fmt.Errorf("bad payload length %d", ln)
		}
		payload := make([]byte, ln)
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, err
		}
		return payload, nil
	}
}

// ------------------------------------------------------------------ viewer

type viewer struct {
	cfg        Config
	absOut     string
	snapDir    string
	eventsPath string
	snapCh     chan struct{}

	img    *canvas.Image
	status *widget.Label

	quit     atomic.Bool
	stopCh   chan struct{}
	stopOnce sync.Once

	frames     uint64
	decodeErr  uint64
	saved      uint64
	savedBytes uint64
	motion     uint64
	snapshots  uint64
}

func (v *viewer) requestStop() {
	v.quit.Store(true)
	v.stopOnce.Do(func() { close(v.stopCh) })
}

func (v *viewer) requestSnap() {
	select {
	case v.snapCh <- struct{}{}:
	default:
	}
}

func (v *viewer) saveSnapshot(payload []byte) {
	name := fmt.Sprintf("snapshot_%s.jpg",
		time.Now().Format("20060102_150405.000000000"))
	if err := os.WriteFile(filepath.Join(v.snapDir, name), payload, 0o644); err != nil {
		log.Printf("snapshot write failed: %v", err)
		return
	}
	atomic.AddUint64(&v.snapshots, 1)
	log.Printf("snapshot saved: %s", name)
}

func (v *viewer) streamLoop() {
	outDir := v.absOut
	every := v.cfg.SaveEvery
	if every < 1 {
		every = 1
	}
	th := v.cfg.MotionThreshold
	var prev image.Image
	var each uint64
	var lastMotion time.Time

	for !v.quit.Load() {
		if v.status != nil {
			fyne.Do(func() { v.status.SetText("connecting to " + v.cfg.URL + " ...") })
		}
		log.Printf("connecting to %s ...", v.cfg.URL)
		var conn *streamConn
		var err error
		if v.cfg.Direct {
			conn, err = dialRaw(v.cfg.URL, v.cfg.Token)
		} else {
			conn, err = dialCurl(v.cfg.URL, v.cfg.Token)
		}
		if err != nil {
			msg := err.Error()
			log.Printf("connect failed: %v", err)
			if v.status != nil {
				fyne.Do(func() { v.status.SetText("connect failed: " + msg + " (retrying in 2s)") })
			}
			if !sleepOrQuit(&v.quit, 2*time.Second) {
				return
			}
			continue
		}
		if v.status != nil {
			fyne.Do(func() { v.status.SetText("streaming from " + v.cfg.URL) })
		}
		log.Printf("streaming from %s", v.cfg.URL)
		br := bufio.NewReaderSize(conn.r, 1<<16)
		for !v.quit.Load() {
			payload, err := nextFrame(br)
			if err != nil {
				log.Printf("stream ended: %v", err)
				break
			}
			atomic.AddUint64(&v.frames, 1)
			select {
			case <-v.snapCh:
				v.saveSnapshot(payload)
			default:
			}
			src, derr := jpeg.Decode(bytes.NewReader(payload))
			if derr != nil {
				atomic.AddUint64(&v.decodeErr, 1)
				if th > 0 {
					prev = nil // do not compare against a corrupt frame
				}
				continue
			}
			mot := false
			var diff float64
			if th > 0 && prev != nil {
				diff = yDiff(prev, src)
				if diff >= th {
					mot = true
					if now := time.Now(); now.Sub(lastMotion) >= 2*time.Second {
						atomic.AddUint64(&v.motion, 1)
						lastMotion = now
					}
				}
			}
			if outDir != "" {
				each++
				if each%uint64(every) == 0 {
					name := filepath.Join(outDir, frameName(time.Now(), mot))
					if werr := os.WriteFile(name, payload, 0o644); werr != nil {
						log.Printf("write %s: %v", name, werr)
					} else {
						atomic.AddUint64(&v.saved, 1)
						atomic.AddUint64(&v.savedBytes, uint64(len(payload)))
						if mot && v.eventsPath != "" {
							logEvent(v.eventsPath, fmt.Sprintf("%s motion frame=%s diff=%.1f\n",
								time.Now().Format(time.RFC3339), filepath.Base(name), diff))
						}
					}
				}
			}
			if v.img != nil {
				v.img.Image = src
				fyne.Do(func() { canvas.Refresh(v.img) })
			}
			prev = src
		}
		closeConn(conn)
		if v.status != nil {
			fyne.Do(func() { v.status.SetText("disconnected - reconnecting") })
		}
		log.Printf("disconnected - reconnecting")
		if !sleepOrQuit(&v.quit, 2*time.Second) {
			return
		}
	}
}

func (v *viewer) statusText(fps float64) string {
	return fmt.Sprintf("fps %.1f  frames %d  saved %d (%.1f MB)  decode errors %d  motion %d  snapshots %d  %s",
		fps,
		atomic.LoadUint64(&v.frames),
		atomic.LoadUint64(&v.saved),
		float64(atomic.LoadUint64(&v.savedBytes))/(1<<20),
		atomic.LoadUint64(&v.decodeErr),
		atomic.LoadUint64(&v.motion),
		atomic.LoadUint64(&v.snapshots),
		v.cfg.URL)
}

func (v *viewer) dashboard() {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	scan := time.NewTicker(30 * time.Second)
	defer scan.Stop()
	var lastN uint64
	lastT := time.Now()
	diskFiles, diskBytes := 0, int64(0)
	if v.absOut != "" {
		diskFiles, diskBytes, _ = DirStats(v.absOut)
	}
	for {
		select {
		case <-tick.C:
			n := atomic.LoadUint64(&v.frames)
			now := time.Now()
			fps := float64(n-lastN) / now.Sub(lastT).Seconds()
			lastN, lastT = n, now
			line := fmt.Sprintf("\r[CameraGate] fps %.1f  frames %d  saved %d (%.1f MB)  decode-err %d  motion %d  snapshots %d  disk %d files (%.1f MB)  retention %s",
				fps,
				n,
				atomic.LoadUint64(&v.saved),
				float64(atomic.LoadUint64(&v.savedBytes))/(1<<20),
				atomic.LoadUint64(&v.decodeErr),
				atomic.LoadUint64(&v.motion),
				atomic.LoadUint64(&v.snapshots),
				diskFiles, float64(diskBytes)/(1<<20),
				v.cfg.RetentionLabel())
			os.Stdout.WriteString(padRight(line, 130) + "\r")
		case <-scan.C:
			if v.absOut != "" {
				diskFiles, diskBytes, _ = DirStats(v.absOut)
			}
		}
	}
}

func (v *viewer) consoleKeys() {
	r := bufio.NewReaderSize(os.Stdin, 64)
	for {
		b, err := r.ReadByte()
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		switch b {
		case 's', 'S':
			v.requestSnap()
			fmt.Print("\nsnapshot requested\n")
		case 'q', 'Q', 0x03:
			fmt.Print("\nquitting\n")
			v.requestStop()
			return
		}
	}
}

func (v *viewer) sweeper() {
	if v.absOut == "" {
		return
	}
	keep := v.cfg.KeepFor()
	if keep <= 0 {
		return
	}
	t := time.NewTicker(15 * time.Minute)
	defer t.Stop()
	for range t.C {
		removed, freed, err := SweepFrames(v.absOut, keep)
		if err != nil {
			log.Printf("periodic sweep: %v", err)
			continue
		}
		if removed > 0 {
			log.Printf("periodic sweep: removed %d frames, freed %.1f MB",
				removed, float64(freed)/(1<<20))
		}
	}
}

// ----------------------------------------------------------------- helpers

func sleepOrQuit(q *atomic.Bool, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if q.Load() {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
	return true
}

func logEvent(path, line string) {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		log.Printf("events log: %v", err)
		return
	}
	f.WriteString(line)
	f.Close()
}

func stdinIsTTY() bool {
	fi, err := os.Stdin.Stat()
	if err != nil {
		return false
	}
	return fi.Mode()&os.ModeCharDevice != 0
}

// ------------------------------------------------------------------- main

func main() {
	urlFlag := flag.String("url", "", "full websocket url (overrides -ip/-port)")
	ip := flag.String("ip", "192.168.0.1", "camera ip address")
	port := flag.String("port", "8080", "camera port")
	token := flag.String("token", "", "auth token (optional)")
	out := flag.String("out", "", "dir to save raw jpeg frames")
	direct := flag.Bool("direct", false, "use raw Go TCP dialer instead of curl transport")
	retention := flag.String("retention", "", `how long to keep frames: 1h, 12h, 24h, 168h, 2.5h ... or "keep" for everything (default 24h)`)
	saveEvery := flag.Int("save-every", 1, "save only every Nth frame")
	gui := flag.Bool("gui", true, "show the desktop viewer window")
	headless := flag.Bool("headless", false, "no GUI; terminal dashboard, s=snapshot q=quit")
	configPath := flag.String("config", "", "path to config file")
	motionTh := flag.Float64("motion", 6, "motion detection threshold (0 disables)")
	flag.Parse()

	explicit := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { explicit[f.Name] = true })

	cfg := DefaultConfig()
	cp := *configPath
	if cp == "" {
		cp = DefaultConfigPath()
	}
	if _, err := os.Stat(cp); err == nil {
		cfg = LoadConfig(cp)
	}

	args := flag.Args()
	if *urlFlag != "" {
		cfg.URL = *urlFlag
	} else if explicit["ip"] || explicit["port"] || len(args) >= 1 {
		ipAddr := *ip
		portS := *port
		if len(args) >= 1 && args[0] != "" {
			ipAddr = args[0]
		}
		if len(args) >= 2 && args[1] != "" {
			portS = args[1]
		}
		cfg.URL = fmt.Sprintf("ws://%s:%s/ws", ipAddr, portS)
	}
	if cfg.URL == "" {
		cfg.URL = fmt.Sprintf("ws://%s:%s/ws", *ip, *port)
	}
	cfg.URL = normalizeURL(cfg.URL)

	if explicit["token"] {
		cfg.Token = *token
	}
	if explicit["out"] {
		cfg.OutDir = *out
	}
	if explicit["direct"] {
		cfg.Direct = *direct
	}
	if explicit["save-every"] {
		cfg.SaveEvery = max(1, *saveEvery)
	}
	if explicit["retention"] {
		if h, ok := parseRetention(*retention); ok {
			cfg.RetentionHours = h
		} else {
			log.Fatalf("bad -retention %q: use e.g. 1h, 12h, 168h, 2.5h or keep", *retention)
		}
	}
	if explicit["gui"] {
		cfg.GUI = *gui
	}
	if *headless {
		cfg.GUI = false
		cfg.Dashboard = true
	}
	if explicit["motion"] {
		cfg.MotionThreshold = *motionTh
	}

	anyConfigFlag := false
	for _, f := range []string{"url", "ip", "port", "token", "out", "direct",
		"retention", "save-every", "gui", "headless", "motion"} {
		if explicit[f] {
			anyConfigFlag = true
			break
		}
	}

	if !anyConfigFlag && !explicit["config"] && stdinIsTTY() {
		start, err := runWizard(&cfg)
		if err != nil {
			log.Printf("setup: %v", err)
			os.Exit(1)
		}
		if !start {
			os.Exit(0)
		}
		if err := cfg.Save(cp); err != nil {
			log.Printf("could not save config: %v", err)
		}
	}

	log.Printf("CameraGate viewer: %s  save to %q  retention %s  motion %s",
		cfg.URL, cfg.OutDir, cfg.RetentionLabel(),
		map[bool]string{true: "on", false: "off"}[cfg.MotionThreshold > 0])

	absOut := ""
	snapDir := ""
	eventsPath := ""
	if cfg.OutDir != "" {
		absOut, _ = filepath.Abs(cfg.OutDir)
		os.MkdirAll(absOut, 0o755)
		snapDir = filepath.Join(absOut, "snapshots")
		eventsPath = filepath.Join(absOut, "events.log")
	} else {
		snapDir, _ = filepath.Abs("snapshots")
	}
	os.MkdirAll(snapDir, 0o755)

	if keep := cfg.KeepFor(); absOut != "" && keep > 0 {
		removed, freed, err := SweepFrames(absOut, keep)
		if err != nil {
			log.Printf("retention sweep: %v", err)
		} else if removed > 0 {
			log.Printf("retention sweep (%s): removed %d old frame(s), freed %.1f MB",
				cfg.RetentionLabel(), removed, float64(freed)/(1<<20))
		} else {
			log.Printf("retention sweep (%s): nothing to remove", cfg.RetentionLabel())
		}
	}

	v := &viewer{
		cfg:        cfg,
		absOut:     absOut,
		snapDir:    snapDir,
		eventsPath: eventsPath,
		snapCh:     make(chan struct{}, 1),
		stopCh:     make(chan struct{}),
	}

	if !cfg.GUI {
		go v.streamLoop()
		go v.dashboard()
		go v.sweeper()
		if stdinIsTTY() {
			go v.consoleKeys()
		}
		<-v.stopCh
		return
	}

	a := app.NewWithID("com.danials.cameragate.viewer")
	w := a.NewWindow("CameraGate stream: " + cfg.URL)
	w.Resize(fyne.NewSize(960, 600))

	img := canvas.NewImageFromImage(nil)
	img.FillMode = canvas.ImageFillContain
	img.SetMinSize(fyne.NewSize(640, 360))

	status := widget.NewLabel("connecting to " + cfg.URL + " ...")
	status.Alignment = fyne.TextAlignCenter

	snapBtn := widget.NewButton("Snapshot (Space)", v.requestSnap)
	v.img = img
	v.status = status

	w.SetContent(container.NewBorder(nil,
		container.NewHBox(snapBtn, status), nil, nil, container.NewStack(img)))
	w.Canvas().SetOnTypedKey(func(e *fyne.KeyEvent) {
		if e.Name == fyne.KeySpace || e.Name == fyne.KeyS {
			v.requestSnap()
		}
	})
	w.Show()

	go v.streamLoop()
	go v.sweeper()
	if cfg.Dashboard {
		go v.dashboard()
	}

	go func() {
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		var lastN uint64
		lastT := time.Now()
		for range tick.C {
			n := atomic.LoadUint64(&v.frames)
			now := time.Now()
			fps := float64(n-lastN) / now.Sub(lastT).Seconds()
			lastN, lastT = n, now
			text := v.statusText(fps)
			fyne.Do(func() { status.SetText(text) })
		}
	}()

	a.Run()
}
