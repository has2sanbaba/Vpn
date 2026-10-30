//go:build android && cgo

package main

/*
#include <jni.h>
#include <stdint.h>

static jsize gfGetArrayLength(JNIEnv* env, jbyteArray arr) {
    if (arr == NULL) return 0;
    return (*env)->GetArrayLength(env, arr);
}

static void gfCopyFromJavaBytes(JNIEnv* env, jbyteArray arr, jsize len, void* dst) {
    if (arr != NULL && len > 0 && dst != NULL) {
        (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte*)dst);
    }
}

static jbyteArray gfNewJavaBytes(JNIEnv* env, const void* data, jsize len) {
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr != NULL && len > 0 && data != NULL) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte*)data);
    }
    return arr;
}
*/
import "C"

import (
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"unsafe"
)

var (
	jniMu         sync.Mutex
	jniGateway    *GatewayServer
	jniControlMux http.Handler
	jniListenPort int
)

func javaBytesToString(env *C.JNIEnv, arr C.jbyteArray) string {
	n := int(C.gfGetArrayLength(env, arr))
	if n <= 0 {
		return ""
	}
	buf := make([]byte, n)
	C.gfCopyFromJavaBytes(env, arr, C.jsize(n), unsafe.Pointer(&buf[0]))
	return string(buf)
}

func stringToJavaBytes(env *C.JNIEnv, s string) C.jbyteArray {
	b := []byte(s)
	if len(b) == 0 {
		return C.gfNewJavaBytes(env, nil, 0)
	}
	return C.gfNewJavaBytes(env, unsafe.Pointer(&b[0]), C.jsize(len(b)))
}

//export Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeStart
func Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeStart(
	env *C.JNIEnv,
	_ C.jclass,
	jDataDir C.jbyteArray,
	jCaPemPath C.jbyteArray,
	jFbConfigPath C.jbyteArray,
	jCustomURI C.jbyteArray,
) C.jint {
	jniMu.Lock()
	defer jniMu.Unlock()

	if jniGateway != nil && jniListenPort > 0 && jniGateway.Addr() != "" {
		return C.jint(jniListenPort)
	}

	dataDir := strings.TrimSpace(javaBytesToString(env, jDataDir))
	caPemPath := strings.TrimSpace(javaBytesToString(env, jCaPemPath))
	fbConfigPath := strings.TrimSpace(javaBytesToString(env, jFbConfigPath))
	customURI := strings.TrimSpace(javaBytesToString(env, jCustomURI))

	if caPemPath != "" {
		_ = os.Setenv("SSL_CERT_FILE", caPemPath)
	}
	_ = os.Setenv("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Printf("[gfproxy-jni] failed binding loopback listener: %v", err)
		return C.jint(-1)
	}
	port := ln.Addr().(*net.TCPAddr).Port

	doh := NewDoHResolver()
	initialCfg := RuntimeConfig{
		Mode:         ModeAntiDPI,
		CustomURI:    customURI,
		FragEnabled:  true,
		FragSize:     24,
		FragDelayMs:  3,
		FbConfigPath: fbConfigPath,
		ProfileName:  "Dr Browser Multi-Source Smart Bypass",
	}

	router := NewUpstreamRouter(doh, initialCfg)
	pool := NewNodePool(doh, dataDir)
	if customURI != "" {
		_ = pool.SetCustomURI(customURI)
	}
	pool.StartBackground()

	racer := NewConnectionRacer(router, pool, dataDir)
	gw := NewGatewayServer(router, doh, pool, racer)
	ctrlMux := NewControlMux(gw, router, doh, "")

	jniGateway = gw
	jniControlMux = ctrlMux
	jniListenPort = port

	go func() {
		if serveErr := gw.ServeListener(ln); serveErr != nil {
			log.Printf("[gfproxy-jni] gateway stopped: %v", serveErr)
		}
	}()

	return C.jint(port)
}

//export Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeControl
func Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeControl(
	env *C.JNIEnv,
	_ C.jclass,
	jMethod C.jbyteArray,
	jPath C.jbyteArray,
	jBody C.jbyteArray,
) C.jbyteArray {
	jniMu.Lock()
	mux := jniControlMux
	jniMu.Unlock()

	method := javaBytesToString(env, jMethod)
	path := javaBytesToString(env, jPath)
	body := javaBytesToString(env, jBody)

	_, resp := InvokeControlHandlerInMemory(mux, method, path, body)
	return stringToJavaBytes(env, resp)
}

//export Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeStop
func Java_org_greatfire_browser_vpn_NativeProxyBridge_nativeStop(
	_ *C.JNIEnv,
	_ C.jclass,
) {
	jniMu.Lock()
	gw := jniGateway
	jniGateway = nil
	jniControlMux = nil
	jniListenPort = 0
	jniMu.Unlock()

	if gw != nil {
		_ = gw.Close()
	}
}
