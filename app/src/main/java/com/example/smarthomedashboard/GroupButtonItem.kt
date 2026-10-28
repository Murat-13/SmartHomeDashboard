package com.example.smarthomedashboard

data class GroupButtonItem(
    val entityId: String,
    val name: String,
    val icon: String = "",
    var state: String = "off",
    var isAvailable: Boolean = true
)