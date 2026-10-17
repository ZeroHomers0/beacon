package httpapi

import (
	"encoding/json"
	"strconv"
	"unicode/utf8"
)

func validJSONUnicode(raw []byte) bool {
	if !utf8.Valid(raw) || !json.Valid(raw) {
		return false
	}
	quoted := false
	for i := 0; i < len(raw); i++ {
		if raw[i] == '"' {
			quoted = !quoted
		} else if quoted && raw[i] == '\\' {
			i++
			if raw[i] != 'u' {
				continue
			}
			unit, err := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
			if err != nil {
				return false
			}
			i += 4
			if unit >= 0xd800 && unit <= 0xdbff {
				if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
					return false
				}
				next, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
				if err != nil || next < 0xdc00 || next > 0xdfff {
					return false
				}
				i += 6
			} else if unit >= 0xdc00 && unit <= 0xdfff {
				return false
			}
		}
	}
	return true
}
