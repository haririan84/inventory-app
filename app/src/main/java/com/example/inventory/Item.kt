package com.example.inventory

data class Item(
    val barcode: String,
    var name: String,
    var price: Double,
    var qty: Int
)
