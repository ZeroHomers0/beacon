// Package auth owns random credential generation and one-way token hashing.
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
)

func NewToken() (string, error) {
	b := make([]byte, 32)
	_, err := rand.Read(b)
	return hex.EncodeToString(b), err
}

func HashToken(token string) string {
	value := sha256.Sum256([]byte(token))
	return hex.EncodeToString(value[:])
}
