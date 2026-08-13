package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync/atomic"

	"github.com/gorilla/websocket"
)

func main() {
	url := flag.String("url", "ws://192.168.0.52:8080/ws", "websocket url")
	out := flag.String("out", "frames", "directory to save jpeg frames")
	token := flag.String("token", "", "auth token (optional)")
	flag.Parse()

	if err := os.MkdirAll(*out, 0o755); err != nil {
		log.Fatal(err)
	}

	u := *url
	if *token != "" {
		u += "?token=" + *token
	}

	conn, _, err := websocket.DefaultDialer.Dial(u, nil)
	if err != nil {
		log.Fatalf("dial %s: %v", u, err)
	}
	defer conn.Close()
	log.Printf("connected to %s", u)

	var n int64
	save := func(msg []byte) {
		idx := atomic.AddInt64(&n, 1)
		name := filepath.Join(*out, fmt.Sprintf("frame_%06d.jpg", idx))
		if err := os.WriteFile(name, msg, 0o644); err != nil {
			log.Printf("write %s: %v", name, err)
			return
		}
		log.Printf("saved %s (%d bytes)", name, len(msg))
	}

	for {
		msgType, msg, err := conn.ReadMessage()
		if err != nil {
			log.Printf("read: %v", err)
			return
		}
		if msgType == websocket.BinaryMessage {
			save(msg)
		}
	}
}