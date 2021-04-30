package com.catink123.touchpad

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class InputCoords (val x: Float?, val y: Float?, val maxX: Float?, val maxY: Float?)