package nl.giejay.android.tv.immich.search

import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import nl.giejay.android.tv.immich.shared.PrivateThumbnails
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.util.ApiUtil

/** Thumbnails only; no original/background-image prefetch, metadata expansion or album lookups. */
internal class PhotoSearchGridAdapter(
    private val sessionValid: () -> Boolean,
    private val focused: (Int) -> Unit,
    private val clicked: (Asset) -> Unit,
    private val leaveGrid: (Int, Int) -> Boolean
) : RecyclerView.Adapter<PhotoSearchGridAdapter.Holder>() {
    private var items: List<Asset> = emptyList()
    private var queryGeneration: Long? = null
    private var submission = 0L
    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)
    override fun getItemCount() = items.size

    /** Commits data before invoking the callback, even for an unchanged/empty list.
     * A callback may synchronously submit newer data; older callbacks are then discarded. */
    fun submit(next: List<Asset>, generation: Long, onCommitted: (Long) -> Unit = {}) {
        val token = ++submission
        val changedQuery = queryGeneration != generation
        queryGeneration = generation
        val old = items
        items = next
        if (changedQuery) notifyDataSetChanged()
        else if (next !== old) {
            if (next.size >= old.size && next.take(old.size) == old) {
                if (next.size > old.size) notifyItemRangeInserted(old.size, next.size - old.size)
            } else notifyDataSetChanged()
        }
        if (isCurrentSubmission(token)) onCommitted(token)
    }

    fun isCurrentSubmission(token: Long): Boolean = submission == token

    /** Called before detaching/destroying the grid so posted commit callbacks become inert. */
    fun invalidate() {
        submission++
        queryGeneration = null
        items = emptyList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        return Holder(ImageView(parent.context).apply {
            isSaveEnabled = false
            isFocusable = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = context.getDrawable(R.drawable.tv_menu_focus)
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (122 * density).toInt()).apply {
                setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
        })
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val asset = items[position]
        val boundGeneration = queryGeneration
        fun currentPosition(): Int {
            val index = holder.bindingAdapterPosition
            return if (boundGeneration == queryGeneration && index != RecyclerView.NO_POSITION && items.getOrNull(index)?.id == asset.id) index else RecyclerView.NO_POSITION
        }
        holder.image.contentDescription = holder.image.context.getString(
            if (asset.type == "VIDEO") R.string.search_video_result else R.string.search_photo_result, position + 1
        )
        holder.image.setOnFocusChangeListener { view, hasFocus ->
            view.alpha = if (hasFocus) 1f else 0.8f
            if (hasFocus && sessionValid()) {
                val index = currentPosition()
                if (index != RecyclerView.NO_POSITION) focused(index)
            }
        }
        holder.image.setOnClickListener { if (sessionValid() && currentPosition() != RecyclerView.NO_POSITION) clicked(asset) }
        holder.image.setOnKeyListener { _, code, event ->
            val index = currentPosition()
            event.action == KeyEvent.ACTION_DOWN && index != RecyclerView.NO_POSITION && sessionValid() &&
                ((code == KeyEvent.KEYCODE_DPAD_UP && index < 4) ||
                    (code == KeyEvent.KEYCODE_DPAD_DOWN && index / 4 == (itemCount - 1) / 4)) && leaveGrid(code, index)
        }
        if (sessionValid()) Glide.with(holder.image)
            .load(PrivateThumbnails.model(ApiUtil.getThumbnailUrl(asset.id, "thumbnail"), asset.fileModifiedAt?.time?.toString().orEmpty()))
            .listener(PrivateThumbnails.guard())
            .into(holder.image)
        else { Glide.with(holder.image).clear(holder.image); holder.image.setImageDrawable(null) }
    }

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.image).clear(holder.image)
        holder.image.setImageDrawable(null)
        holder.image.setOnClickListener(null)
        holder.image.setOnKeyListener(null)
        holder.image.onFocusChangeListener = null
        super.onViewRecycled(holder)
    }
}
