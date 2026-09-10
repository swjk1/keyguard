package com.keyguard.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.keyguard.app.R

interface EmojiPanelListener {
    fun onEmoji(emoji: String)
    fun onEmojiBackspace()
    fun onEmojiClose()
}

/**
 * A compact emoji picker.
 *
 * Not a full emoji keyboard — no skin-tone modifiers, no search, no full Unicode coverage. It
 * exists because "no emoji at all" is a reason people uninstall a keyboard within the hour, and
 * a few hundred common emoji covers the overwhelming majority of real use.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val listener: EmojiPanelListener,
) : LinearLayout(context) {

    /** [emoji] is a space-separated list, split lazily when the category is shown. */
    private data class Category(val label: String, val emoji: String)

    private val categories = listOf(
        Category(
            "😀",
            "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 " +
                "🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 " +
                "🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 " +
                "😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖",
        ),
        Category(
            "👍",
            "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👋 🤚 🖐️ ✋ 🖖 👏 🙌 🤝 🙏 💪 " +
                "🦾 ✍️ 💅 🤳 💃 🕺 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵",
        ),
        Category(
            "❤️",
            "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🕳️ " +
                "✨ 🌟 ⭐ 🔥 🎉 🎊 🎈 🎁 🏆 🥇 🥈 🥉",
        ),
        Category(
            "🐶",
            "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 " +
                "🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🐋 🦈",
        ),
        Category(
            "🍕",
            "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🌶️ 🥔 🍠 " +
                "🥐 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🥗 🍝 🍜 🍣 🍤 🍦 🍩 🍪 " +
                "🎂 🍰 🧁 🍫 🍬 🍭 ☕ 🍵 🥤 🧋",
        ),
        Category(
            "⚽",
            "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎽 🎿 🛹 🎮 🕹️ 🎲 🧩 🎯 🎳 " +
                "🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚲 🛴 🏍️ ✈️ 🚀 🛸 🚁 ⛵ 🚢",
        ),
        Category(
            "🌍",
            "🌍 🌎 🌏 🌝 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ " +
                "⛄ 🌬️ 💧 🌊 🌈 🌸 💐 🌹 🌺 🌻 🌼 🌷 🌱 🌲 🌳 🌴 🌵 🍀 🍁 🍂",
        ),
    )

    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
    }

    private var bottomInsetPx = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_background))

        addView(buildCategoryBar())

        addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(grid)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            },
        )

        addView(buildBottomBar())
        showCategory(0)
    }

    /**
     * Top row: the back-to-keyboard button followed by the scrollable category list.
     *
     * "ABC" lives up here rather than in the bottom bar, where emoji pickers conventionally put
     * it, because the bottom edge of an IME is exactly where the system draws its own
     * hide-keyboard control. A button there ends up a few millimetres from a control that
     * dismisses the keyboard entirely, and mistaking one for the other is a genuinely annoying
     * misfire. The top row cannot collide with anything.
     */
    private fun buildCategoryBar(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        categories.forEachIndexed { index, category ->
            row.addView(
                TextView(context).apply {
                    text = category.label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    isClickable = true
                    setOnClickListener { showCategory(index) }
                },
            )
        }

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))

            addView(
                TextView(context).apply {
                    text = context.getString(R.string.emoji_close)
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(context.getColor(R.color.key_text))
                    setBackgroundResource(R.drawable.key_background_modifier)
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    isClickable = true
                    contentDescription = context.getString(R.string.emoji_close_description)
                    setOnClickListener { listener.onEmojiClose() }
                    layoutParams = LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8) }
                },
            )
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }
    }

    /** Bottom row: backspace only, right-aligned and clear of the system's own controls. */
    private fun buildBottomBar(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(6))

        addView(android.view.View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        addView(
            TextView(context).apply {
                text = context.getString(R.string.key_backspace)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(context.getColor(R.color.key_text))
                setBackgroundResource(R.drawable.key_background_modifier)
                setPadding(dp(24), dp(10), dp(24), dp(10))
                isClickable = true
                contentDescription = context.getString(R.string.key_backspace_description)
                setOnClickListener { listener.onEmojiBackspace() }
            },
        )
    }

    /**
     * Matches the key grid's navigation-bar inset. Without this the panel's bottom row sits
     * lower than the keyboard's does and crowds the system controls — the original cause of
     * the ABC button feeling too close to the keyboard-dismiss arrow.
     */
    fun applyBottomInset(insetPx: Int) {
        if (insetPx == bottomInsetPx) return
        bottomInsetPx = insetPx
        setPadding(paddingLeft, paddingTop, paddingRight, insetPx)
    }

    private fun showCategory(index: Int) {
        grid.removeAllViews()
        val emoji = categories[index].emoji
            .split(" ")
            .filter { it.isNotBlank() }

        for (glyph in emoji) {
            grid.addView(
                TextView(context).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener { listener.onEmoji(glyph) }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 8
    }
}
