package com.fluttercandies.photo_manager.core.entity.filter

import android.annotation.SuppressLint
import android.provider.MediaStore
import com.fluttercandies.photo_manager.constant.AssetType
import com.fluttercandies.photo_manager.core.utils.ConvertUtils
import com.fluttercandies.photo_manager.core.utils.RequestTypeUtils
import java.util.ArrayList

class CommonFilterOption(map: Map<*, *>) : FilterOption() {
    private val videoOption = ConvertUtils.getOptionFromType(map, AssetType.Video)
    private val imageOption = ConvertUtils.getOptionFromType(map, AssetType.Image)
    private val audioOption = ConvertUtils.getOptionFromType(map, AssetType.Audio)
    private val createDateCond = ConvertUtils.convertToDateCond(map["createDate"] as Map<*, *>)
    private val updateDateCond = ConvertUtils.convertToDateCond(map["updateDate"] as Map<*, *>)
    private val takenDateCond = ConvertUtils.convertToDateCond(map["takenDate"] as Map<*, *>)
    override val containsPathModified = map["containsPathModified"] as Boolean

    private val orderByCond: List<OrderByCond> =
        ConvertUtils.convertToOrderByConds(map["orders"] as List<*>)

    override fun makeWhere(requestType: Int, args: ArrayList<String>, needAnd: Boolean): String {
        val option = this
        val typeSelection: String = getCondFromType(requestType, option, args)
        val dateSelection = getDateCond(args, option)
        val sizeWhere = sizeWhere(requestType, option)
        return "$typeSelection $dateSelection $sizeWhere"
    }

    override fun orderByCondString(): String? {
        if (orderByCond.isEmpty()) {
            return null
        }
        return orderByCond.joinToString(",") {
            it.getOrder()
        }
    }

    private val typeUtils: RequestTypeUtils
        get() = RequestTypeUtils

    /**
     * Just filter [MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE]
     */
    private fun sizeWhere(requestType: Int?, option: CommonFilterOption): String {
        if (option.imageOption.sizeConstraint.ignoreSize) {
            return ""
        }
        if (requestType == null || !typeUtils.containsImage(requestType)) {
            return ""
        }
        val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
        var result = ""
        if (typeUtils.containsVideo(requestType)) {
            result = "OR ( $mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} )"
        }
        if (typeUtils.containsAudio(requestType)) {
            result = "$result OR ( $mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} )"
        }
        val size = "${MediaStore.MediaColumns.WIDTH} > 0 AND ${MediaStore.MediaColumns.HEIGHT} > 0"
        val imageCondString =
            "( $mediaType = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND $size )"
        result = "AND ($imageCondString $result)"
        return result
    }

    private fun getCondFromType(
        type: Int, filterOption: CommonFilterOption, args: ArrayList<String>
    ): String {
        val cond = StringBuilder()
        val typeKey = MediaStore.Files.FileColumns.MEDIA_TYPE

        val haveImage = RequestTypeUtils.containsImage(type)
        val haveVideo = RequestTypeUtils.containsVideo(type)
        val haveAudio = RequestTypeUtils.containsAudio(type)

        var imageCondString = ""
        var videoCondString = ""
        var audioCondString = ""

        if (haveImage) {
            val imageCond = filterOption.imageOption
            imageCondString = "$typeKey = ? "
            args.add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            if (!imageCond.sizeConstraint.ignoreSize) {
                val sizeCond = imageCond.sizeCond()
                val sizeArgs = imageCond.sizeArgs()
                imageCondString = "$imageCondString AND $sizeCond"
                args.addAll(sizeArgs)
            }
        }

        if (haveVideo) {
            val videoCond = filterOption.videoOption
            val durationCond = videoCond.durationCond()
            val durationArgs = videoCond.durationArgs()
            videoCondString = "$typeKey = ? AND $durationCond"
            args.add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            args.addAll(durationArgs)
        }

        if (haveAudio) {
            val audioCond = filterOption.audioOption
            val durationCond = audioCond.durationCond()
            val durationArgs = audioCond.durationArgs()
            audioCondString = "$typeKey = ? AND $durationCond"
            args.add(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            args.addAll(durationArgs)
        }

        if (haveImage) {
            cond.append("( $imageCondString )")
        }

        if (haveVideo) {
            if (cond.isNotEmpty()) {
                cond.append("OR ")
            }
            cond.append("( $videoCondString )")
        }

        if (haveAudio) {
            if (cond.isNotEmpty()) {
                cond.append("OR ")
            }
            cond.append("( $audioCondString )")
        }

        return "AND ( $cond )"
    }


    private fun getDateCond(args: ArrayList<String>, option: CommonFilterOption): String {
        val createDateCond =
            addDateCond(args, option.createDateCond, MediaStore.Images.Media.DATE_ADDED)
        val updateDateCond =
            addDateCond(args, option.updateDateCond, MediaStore.Images.Media.DATE_MODIFIED)
        val takenDateCond = addDateCond(args, option.takenDateCond, MediaStore.Images.Media.DATE_TAKEN)

        return "$createDateCond $updateDateCond $takenDateCond"
    }

    private fun addDateCond(args: ArrayList<String>, dateCond: DateCond, dbKey: String): String {
        if (dateCond.ignore) {
            return ""
        }

        val dateSelection = "AND ( $dbKey >= ? AND $dbKey <= ? )"

        var minMs = dateCond.minMs
        var maxMs = dateCond.maxMs

        if (dbKey != MediaStore.Images.Media.DATE_TAKEN) {
            minMs /= 1000
            maxMs /= 1000
        }

        args.add(minMs.toString())
        args.add(maxMs.toString())

        return dateSelection
    }

}

class FilterCond {
    var isShowTitle = false
    lateinit var sizeConstraint: SizeConstraint
    lateinit var durationConstraint: DurationConstraint

    companion object {
        private const val widthKey = MediaStore.Files.FileColumns.WIDTH
        private const val heightKey = MediaStore.Files.FileColumns.HEIGHT

        @SuppressLint("InlinedApi")
        private const val durationKey = MediaStore.Video.VideoColumns.DURATION
    }

    fun sizeCond(): String =
        "$widthKey >= ? AND $widthKey <= ? AND $heightKey >= ? AND $heightKey <=?"

    fun sizeArgs(): Array<String> {
        return arrayOf(
            sizeConstraint.minWidth,
            sizeConstraint.maxWidth,
            sizeConstraint.minHeight,
            sizeConstraint.maxHeight
        ).toList().map {
            it.toString()
        }.toTypedArray()
    }

    fun durationCond(): String {
        val baseCond = "$durationKey >=? AND $durationKey <=?"
        if (durationConstraint.allowNullable) {
            return "( $durationKey IS NULL OR ( $baseCond ) )"
        }
        return baseCond
    }

    fun durationArgs(): Array<String> {
        return arrayOf(
            durationConstraint.min, durationConstraint.max
        ).map { it.toString() }.toTypedArray()
    }

    class SizeConstraint {
        var minWidth = 0
        var maxWidth = 0
        var minHeight = 0
        var maxHeight = 0
        var ignoreSize = false
    }

    class DurationConstraint {
        var min: Long = 0
        var max: Long = 0
        var allowNullable: Boolean = false
    }
}

data class DateCond(
    val minMs: Long, val maxMs: Long, val ignore: Boolean
)

data class OrderByCond(
    val key: String, val asc: Boolean
) {
    fun getOrder(): String {
        val ascValue = if (asc) {
            "asc"
        } else {
            "desc"
        }
        return "$key $ascValue"
    }
}
