package com.opticalgenesis.jfelt.g6camera

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView


// TODO -- 2017/11/29 -- Fix this?
class AutofitTextureVew(c: Context) : TextureView(c) {
    constructor(c: Context, attrs: AttributeSet) : this(c)
    constructor(c: Context, attrs: AttributeSet, defStyle: Int) : this(c, attrs)

    var ratioWidth = 0
    var ratioHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            if (width < height * ratioWidth / ratioHeight) {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            } else {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            }
        }
    }

    private fun setAspectRatio(w: Int, h: Int) {
        if (w < 0 || h < 0) throw IllegalArgumentException("Dimension cannot be less than 0")

        ratioWidth = w
        ratioHeight = h

        requestLayout()
    }
}