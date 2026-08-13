package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"flag"
	"fmt"
	"image/jpeg"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
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

func dialCurl(url, token string) (*streamConn, error) {
	httpURL := strings.Replace(url, "ws://", "http://", 1)
	httpURL = strings.Replace(httpURL, "wss://", "https://", 1)
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

func dialRaw(url, token string) (*streamConn, error) {
	host, port, err := net.SplitHostPort(trimScheme(url))
	if err != nil {
		return nil, err
	}
	if host == "" {
		host = "127.0.0.1"
	}
	if port == "" {
		port = "80"
	}
	conn, err := net.DialTimeout("tcp", host+":"+port, 5*time.Second)
	if err != nil {
		return nil, err
	}
	key := make([]byte, 16)
	rand.Read(key)
	keyStr := base64.StdEncoding.EncodeToString(key)
	path := "/ws"
	if token != "" {
		path += "?token=" + token
	}
	req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\n"+
		"Upgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n",
		path, host, keyStr)
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

func trimScheme(u string) string {
	for _, p := range []string{"ws://", "wss://", "http://", "https://"} {
		if len(u) >= len(p) && u[:len(p)] == p {
			return u[len(p):]
		}
	}
	return u
}

func closeConn(c *streamConn) {
	if c.n != nil {
		c.n.Close()
	}
	if c.c != nil {
		c.c.Process.Kill()
	}
}

// nextFrame reads one binary websocket frame payload (with 126/127 extended
// lengths). Returns nil on stream end.
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

func streamLoop(direct bool, url, token, outDir string,
	img *canvas.Image, status *widget.Label, frames, decodeErr *uint64) {
	for {
		fyne.Do(func() { status.SetText("connecting to " + url + " ...") })
		var conn *streamConn
		var err error
		if direct {
			conn, err = dialRaw(url, token)
		} else {
			conn, err = dialCurl(url, token)
		}
		if err != nil {
			msg := err.Error()
			fyne.Do(func() { status.SetText("connect failed: " + msg + " (retrying in 2s)") })
			time.Sleep(2 * time.Second)
			continue
		}
		open := url
		fyne.Do(func() { status.SetText("streaming from " + open) })
		br := bufio.NewReaderSize(conn.r, 1<<16)
		for {
			payload, err := nextFrame(br)
			if err != nil {
				log.Printf("stream ended: %v", err)
				break
			}
			if outDir != "" {
				name := filepath.Join(outDir, fmt.Sprintf("frame_%08d.jpg",
					atomic.AddUint64(frames, 1)))
				if werr := os.WriteFile(name, payload, 0o644); werr != nil {
					log.Printf("write %s: %v", name, werr)
				}
			}
			src, derr := jpeg.Decode(bytes.NewReader(payload))
			if derr != nil {
				atomic.AddUint64(decodeErr, 1)
				continue
			}
			atomic.AddUint64(frames, 1)
			img.Image = src
			fyne.Do(func() { canvas.Refresh(img) })
		}
		closeConn(conn)
		fyne.Do(func() { status.SetText("disconnected - reconnecting in 2s") })
		time.Sleep(2 * time.Second)
	}
}

func main() {
	url := flag.String("url", "ws://192.168.0.52:8080/ws", "websocket url")
	token := flag.String("token", "", "auth token (optional)")
	out := flag.String("out", "", "optional dir to save raw jpeg frames")
	direct := flag.Bool("direct", false, "use raw Go TCP dialer instead of curl transport")
	flag.Parse()

	a := app.NewWithID("com.danials.cameragate.viewer")
	w := a.NewWindow("CameraGate stream: " + *url)
	w.Resize(fyne.NewSize(960, 600))

	img := canvas.NewImageFromImage(nil)
	img.FillMode = canvas.ImageFillContain
	img.SetMinSize(fyne.NewSize(640, 360))

	status := widget.NewLabel("connecting to " + *url + " ...")
	status.Alignment = fyne.TextAlignCenter

	w.SetContent(container.NewBorder(nil, status, nil, nil, container.NewStack(img)))
	w.Show()

	var frames, decodeErr uint64

	go streamLoop(*direct, *url, *token, *out, img, status, &frames, &decodeErr)

	go func() {
		tick := time.NewTicker(2 * time.Second)
		for range tick.C {
			f := atomic.LoadUint64(&frames)
			d := atomic.LoadUint64(&decodeErr)
			u := *url
			fyne.Do(func() {
				status.SetText(fmt.Sprintf("frames: %d  decode errors: %d  (%s)", f, d, u))
			})
		}
	}()

	a.Run()
}