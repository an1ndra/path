#!/usr/bin/env python3
"""Mock upstream provider for exercising the gateway.

Serves both wire formats the gateway speaks:
  POST /v1/chat/completions   OpenAI (stream and non-stream)
  POST /v1/messages           Anthropic Messages (stream and non-stream)
  GET  /v1/models             both flavours

Behaviour switches are driven by the upstream model id in the request body:
  * "boom"        -> HTTP 500
  * "ratelimit"   -> HTTP 429
  * "slow"        -> stream sleeps 4s between chunks
  * "empty"       -> stream sends nothing then closes
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9099


def openai_body(model, prompt_tokens=11, completion_tokens=7):
    return {
        "id": "chatcmpl-mock123",
        "object": "chat.completion",
        "created": 1700000000,
        "model": model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": "Hello from the mock upstream."},
            "finish_reason": "stop",
        }],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
            "prompt_tokens_details": {"cached_tokens": 5},
            "completion_tokens_details": {"reasoning_tokens": 0},
        },
    }


def anthropic_body(model):
    return {
        "id": "msg_mock123",
        "type": "message",
        "role": "assistant",
        "model": model,
        "content": [{"type": "text", "text": "Hello from mock Anthropic."}],
        "stop_reason": "end_turn",
        "stop_sequence": None,
        "usage": {"input_tokens": 13, "output_tokens": 9,
                  "cache_read_input_tokens": 4, "cache_creation_input_tokens": 2},
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("mock %s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, code, payload):
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _read(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        if self.path.endswith("/models"):
            data = [
                {"id": "gpt-4o-mini", "object": "model"},
                {"id": "gpt-4o", "object": "model"},
                {"id": "text-embedding-3-small", "object": "model"},
                {"id": "claude-3-5-haiku-latest", "object": "model"},
                {"id": "llama-3.3-70b-versatile", "object": "model"},
                {"id": "acme-unknown-model", "object": "model"},
            ]
            self._json(200, {"object": "list", "data": data})
        else:
            self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        body = self._read()
        model = str(body.get("model", ""))
        stream = bool(body.get("stream"))

        if "boom" in model:
            self._json(500, {"error": {"message": "mock exploded", "type": "server_error"}})
            return
        if "ratelimit" in model:
            self._json(429, {"error": {"message": "mock rate limit", "type": "rate_limit"}})
            return
        if "unauthorized" in model:
            self._json(401, {"error": {"message": "bad key", "type": "invalid_api_key"}})
            return
        if "badrequest" in model:
            self._json(400, {"error": {"message": "bad request from client", "type": "invalid_request"}})
            return

        if self.path.endswith("/embeddings"):
            self._json(200, {
                "object": "list",
                "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
                "model": model,
                "usage": {"prompt_tokens": 7, "total_tokens": 7},
            })
        elif self.path.endswith("/messages"):
            self._anthropic(model, stream)
        else:
            self._openai(model, stream)

    # ---------------------------------------------------------------- OpenAI

    def _openai(self, model, stream):
        if not stream:
            self._json(200, openai_body(model))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def send(obj):
            self.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
            self.wfile.flush()

        base = {
            "id": "chatcmpl-mock123", "object": "chat.completion.chunk",
            "created": 1700000000, "model": model,
        }
        if "empty" in model:
            self.close_connection = True
            return
        send({**base, "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}]})
        for piece in ["Hello", " from", " mock", " stream"]:
            if "slow" in model:
                time.sleep(4)
            send({**base, "choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}]})
        send({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
        send({**base, "choices": [], "usage": {
            "prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18,
            "prompt_tokens_details": {"cached_tokens": 5}}})
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    # ------------------------------------------------------------- Anthropic

    def _anthropic(self, model, stream):
        if not stream:
            self._json(200, anthropic_body(model))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        def event(name, payload):
            self.wfile.write(b"event: " + name.encode() + b"\n")
            self.wfile.write(b"data: " + json.dumps(payload).encode() + b"\n\n")
            self.wfile.flush()

        event("message_start", {"type": "message_start", "message": {
            "id": "msg_mock123", "type": "message", "role": "assistant", "model": model,
            "content": [], "usage": {"input_tokens": 13, "output_tokens": 0,
                                     "cache_read_input_tokens": 4,
                                     "cache_creation_input_tokens": 2}}})
        event("content_block_start", {"type": "content_block_start", "index": 0,
                                      "content_block": {"type": "text", "text": ""}})
        for piece in ["Hello", " from", " mock", " Anthropic", "."]:
            event("content_block_delta", {"type": "content_block_delta", "index": 0,
                                          "delta": {"type": "text_delta", "text": piece}})
        event("content_block_stop", {"type": "content_block_stop", "index": 0})
        event("message_delta", {"type": "message_delta",
                                "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                                "usage": {"output_tokens": 9}})
        event("message_stop", {"type": "message_stop"})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"mock upstream listening on 127.0.0.1:{PORT}", flush=True)
    server.serve_forever()
