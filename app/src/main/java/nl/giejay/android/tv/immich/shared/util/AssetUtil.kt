package nl.giejay.android.tv.immich.shared.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_LOAD_EDITED_PHOTO
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun List<Asset>.toSliderItems(
    keepOrder: Boolean,
    mergePortrait: Boolean,
    smartPortrait: Boolean = false
): List<SliderItemViewHolder> {
    if (!mergePortrait) {
        return this.map { SliderItemViewHolder(it.toSliderItem()) }
    }
    val pairs = pairPortraitAssets(keepOrder = keepOrder && !smartPortrait)
    val items = pairs.map { SliderItemViewHolder(it.first.toSliderItem(), it.second?.toSliderItem()) }
    return if (keepOrder) items else items.shuffled()
}

fun Asset.toSliderItem(): SliderItem {
    val itemType = SliderItemType.valueOf(this.type.uppercase())
    return SliderItem(
        this.id,
        ApiUtil.getFileUrl(
            this.id,
            this.type,
            PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
            PreferenceManager.get(SLIDER_LOAD_EDITED_PHOTO)
        ),
        itemType,
        this.exifInfo?.orientation ?: if (itemType == SliderItemType.IMAGE) 1 else 6,
        AssetMetaDataMapping.providersFor(this),
        ApiUtil.getThumbnailUrl(this.id, "preview", PreferenceManager.get(SLIDER_LOAD_EDITED_PHOTO)),
        isPanorama = this.isPanoramaImage(),
        isFavorite = this.isFavorite
    )
}

internal fun formatAssetDate(date: Date): String {
    val calendar = Calendar.getInstance()
    calendar.time = date
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val isEnglish = locale.language == "en"
    val day = calendar[Calendar.DATE]
    val formatString = if (isEnglish) {
        // English: Friday, 7th April 2006
        when (day) {
            1, 21, 31 -> "EEEE, d'st' MMMM yyyy"
            2, 22 -> "EEEE, d'nd' MMMM yyyy"
            3, 23 -> "EEEE, d'rd' MMMM yyyy"
            else -> "EEEE, d'th' MMMM yyyy"
        }
    } else {
        // All other locales: Freitag, 7. April 2006
        "EEEE, d. MMMM yyyy"
    }
    return SimpleDateFormat(formatString, locale).format(date)
}

internal fun formatAssetTime(date: Date): String {
    val calendar = Calendar.getInstance()
    calendar.time = date
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val formatString = "H:mm:ss"
    return SimpleDateFormat(formatString, locale).format(date)
}

fun Asset.isPortraitImage(): Boolean {
    val aspectRatio = this.getAspectRatio()
    return (this.exifInfo?.orientation == 6 || this.exifInfo?.orientation == 8 || (aspectRatio != null && aspectRatio > 0.56 && aspectRatio <= 1.1)) && this.type == SliderItemType.IMAGE.toString()
}

fun Asset.isPanoramaImage(): Boolean {
    val aspectRatio = this.getAspectRatio()
    return (aspectRatio != null && (aspectRatio <= 0.56 || aspectRatio > 2.0)) && this.type == SliderItemType.IMAGE.toString()
}

fun Asset.getAspectRatio(): Double? {
    return if(this.exifInfo != null && this.exifInfo.exifImageHeight != null && this.exifInfo.exifImageWidth != null && this.exifInfo.exifImageHeight > 0)
        this.exifInfo.exifImageWidth.toDouble() / this.exifInfo.exifImageHeight.toDouble()
    else
        null
}

fun List<Asset>.toCards(): List<Card> {
    return this.map {
        it.toCard()
    }
}

fun Asset.toCard(): Card {
    return Card(this.originalFileName ?: this.deviceAssetId ?: "",
        this.exifInfo?.description ?: "",
        this.id,
        ApiUtil.getThumbnailUrl(this.id, "thumbnail"),
        ApiUtil.getThumbnailUrl(this.id, "preview"),
        thumbnailVersion = fileModifiedAt?.time?.toString().orEmpty())
}
