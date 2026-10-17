package domain

import "unicode/utf8"

func ValidMessageText(title, subtitle, body string) bool {
	return len(title) <= MaxTitleBytes && len(subtitle) <= MaxTitleBytes &&
		len(body) > 0 && len(body) <= MaxBodyBytes &&
		utf8.ValidString(title) && utf8.ValidString(subtitle) && utf8.ValidString(body)
}
