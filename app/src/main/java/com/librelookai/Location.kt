package com.librelookai

import java.util.UUID

data class Location(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val folderId: String = "",
)
