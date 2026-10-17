package io.github.beacon.app.domain.model

enum class AsyncPageState {
    IDLE,
    INITIAL_LOADING,
    INITIAL_ERROR,
    CONTENT,
    EMPTY,
    REFRESHING,
    REFRESH_ERROR;

    val hasContent: Boolean
        get() = when (this) {
            CONTENT, EMPTY, REFRESHING, REFRESH_ERROR -> true
            else -> false
        }

    companion object {
        fun resolve(
            loaded: Boolean,
            loading: Boolean,
            failed: Boolean,
            empty: Boolean = false,
        ): AsyncPageState = when {
            loading -> if (loaded) REFRESHING else INITIAL_LOADING
            failed -> if (loaded) REFRESH_ERROR else INITIAL_ERROR
            !loaded -> IDLE
            empty -> EMPTY
            else -> CONTENT
        }
    }
}
