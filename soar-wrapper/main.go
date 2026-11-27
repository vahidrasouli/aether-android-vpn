package main

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

type ConfigFile struct {
	Domain               string   `json:"domain"`
	PublicKey            string   `json:"public_key"`
	Resolvers            []string `json:"resolvers"`
	Transport            string   `json:"transport"`
	Discovery            bool     `json:"discovery"`
	Country              string   `json:"country"`
	DiscoveryWant        int      `json:"discoveryWant"`
	DiscoveryConcurrency int      `json:"discoveryConcurrency"`
	DiscoveryPaceMs      int      `json:"discoveryPaceMs"`
	SafeQName            int      `json:"safeQName"`
	MaxQName             int      `json:"maxQName"`
	MaxAnswer            int      `json:"maxAnswer"`
	HoldMs               int      `json:"holdMs"`
	MaxInflight          int      `json:"maxInflight"`
	MaxPathWindow        int      `json:"maxPathWindow"`
	MaxStreams           int      `json:"maxStreams"`
	StreamWindowKiB      int      `json:"streamWindowKiB"`
	SendBufferKiB        int      `json:"sendBufferKiB"`
	SessionIdleSeconds   int      `json:"sessionIdleSeconds"`
	DialTimeoutSeconds   int      `json:"dialTimeoutSeconds"`
}

func main() {
	configPath := flag.String("config", "", "path to soar JSON config file")
	listenAddr := flag.String("listen", "127.0.0.1:1829", "local SOCKS5 listen address")
	flag.Parse()

	if strings.TrimSpace(*configPath) == "" {
		fmt.Fprintln(os.Stderr, "soar-wrapper: --config is required")
		os.Exit(2)
	}

	raw, err := os.ReadFile(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar-wrapper: read config: %v\n", err)
		os.Exit(1)
	}

	var cfgFile ConfigFile
	if err := json.Unmarshal(raw, &cfgFile); err != nil {
		fmt.Fprintf(os.Stderr, "soar-wrapper: parse config: %v\n", err)
		os.Exit(1)
	}

	pubKey, err := decodePublicKey(cfgFile.PublicKey)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar-wrapper: invalid public_key: %v\n", err)
		os.Exit(1)
	}

	resolvers := normalizeResolvers(cfgFile.Resolvers)
	tcpMode := mapTransportToTCP(cfgFile.Transport)

	dialTimeout := 15 * time.Second
	if cfgFile.DialTimeoutSeconds > 0 {
		dialTimeout = time.Duration(cfgFile.DialTimeoutSeconds) * time.Second
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))

	soarCfg := soar.Config{
		Zone:      strings.TrimSpace(cfgFile.Domain),
		ServerKey: pubKey,
		Resolvers: resolvers,
		Discovery: soar.Discovery{
			Enabled:     cfgFile.Discovery,
			Country:     strings.TrimSpace(cfgFile.Country),
			Want:        cfgFile.DiscoveryWant,
			Concurrency: cfgFile.DiscoveryConcurrency,
			Pace:        time.Duration(cfgFile.DiscoveryPaceMs) * time.Millisecond,
		},
		SafeQName:     cfgFile.SafeQName,
		MaxQName:      cfgFile.MaxQName,
		MaxAnswer:     cfgFile.MaxAnswer,
		Hold:          time.Duration(cfgFile.HoldMs) * time.Millisecond,
		MaxInflight:   cfgFile.MaxInflight,
		MaxPathWindow: cfgFile.MaxPathWindow,
		MaxStreams:    cfgFile.MaxStreams,
		StreamWindow:  uint64(maxInt(cfgFile.StreamWindowKiB, 0)) << 10,
		SendBuffer:    maxInt(cfgFile.SendBufferKiB, 0) << 10,
		SessionIdle:   time.Duration(cfgFile.SessionIdleSeconds) * time.Second,
		TCP:           tcpMode,
		Logger:        logger,
	}

	client, err := soar.NewClient(soarCfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar-wrapper: NewClient failed: %v\n", err)
		os.Exit(1)
	}
	defer client.Close()

	ln, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "soar-wrapper: listen %s failed: %v\n", *listenAddr, err)
		os.Exit(1)
	}
	defer ln.Close()

	fmt.Printf("SOCKS5 listening on %s (transport=%s, discovery=%v, country=%s, resolvers=%d)\n",
		ln.Addr().String(), cfgFile.Transport, cfgFile.Discovery, cfgFile.Country, len(resolvers))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go reportStatsLoop(ctx, client)
	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				break
			}
			continue
		}
		go handleSocks5(ctx, conn, client, dialTimeout)
	}
}

func decodePublicKey(raw string) (ed25519.PublicKey, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return nil, errors.New("empty public key")
	}
	if len(s) == ed25519.PublicKeySize*2 {
		if b, err := hex.DecodeString(s); err == nil && len(b) == ed25519.PublicKeySize {
			return ed25519.PublicKey(b), nil
		}
	}
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		if b, err := enc.DecodeString(s); err == nil && len(b) == ed25519.PublicKeySize {
			return ed25519.PublicKey(b), nil
		}
	}
	return nil, fmt.Errorf("expected %d-byte Ed25519 key (base64 or hex)", ed25519.PublicKeySize)
}

func normalizeResolvers(raw []string) []string {
	var out []string
	for _, item := range raw {
		s := strings.TrimSpace(item)
		if s == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(s); err != nil {
			s = net.JoinHostPort(s, "53")
		}
		out = append(out, s)
	}
	return out
}

func mapTransportToTCP(transport string) string {
	switch strings.ToLower(strings.TrimSpace(transport)) {
	case "udp", "off":
		return "off"
	case "tcp", "only":
		return "only"
	case "on":
		return "on"
	default:
		return "auto"
	}
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func reportStatsLoop(ctx context.Context, client *soar.Client) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	lastLine := ""
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			stats := client.PathStats()
			verified := 0
			active := 0
			discovered := 0
			for _, st := range stats {
				if st.Verified {
					verified++
				}
				if st.Verified && !st.Suspended {
					active++
				}
				if st.Candidate {
					discovered++
				}
			}
			line := fmt.Sprintf("Resolvers: active=%d verified=%d discovered=%d total=%d established=%v",
				active, verified, discovered, len(stats), client.Established())
			if line != lastLine {
				fmt.Println(line)
				lastLine = line
			}
		}
	}
}

func handleSocks5(parentCtx context.Context, clientConn net.Conn, client *soar.Client, dialTimeout time.Duration) {
	defer clientConn.Close()
	_ = clientConn.SetDeadline(time.Now().Add(30 * time.Second))

	// 1. Greeting: VER(1) | NMETHODS(1) | METHODS(1..255)
	var hdr [2]byte
	if _, err := io.ReadFull(clientConn, hdr[:]); err != nil {
		return
	}
	if hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	// Reply: No authentication required (0x00)
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Request: VER(1) | CMD(1) | RSV(1) | ATYP(1) | DST.ADDR | DST.PORT(2)
	var req [4]byte
	if _, err := io.ReadFull(clientConn, req[:]); err != nil {
		return
	}
	if req[0] != 0x05 {
		return
	}
	if req[1] != 0x01 { // Only CONNECT
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch req[3] {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	case 0x03: // Domain
		var lenBuf [1]byte
		if _, err := io.ReadFull(clientConn, lenBuf[:]); err != nil {
			return
		}
		dom := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(clientConn, dom); err != nil {
			return
		}
		host = string(dom)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	default:
		_, _ = clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(clientConn, portBuf[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf[:])
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))

	dialCtx, cancel := context.WithTimeout(parentCtx, dialTimeout)
	defer cancel()

	tunnelConn, err := client.DialContext(dialCtx, "tcp", target)
	if err != nil {
		_, _ = clientConn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer tunnelConn.Close()

	_ = clientConn.SetDeadline(time.Time{})
	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(tunnelConn, clientConn)
		_ = tunnelConn.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(clientConn, tunnelConn)
		_ = clientConn.Close()
	}()
	wg.Wait()
}
