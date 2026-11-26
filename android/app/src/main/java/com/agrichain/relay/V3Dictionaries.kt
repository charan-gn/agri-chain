package com.agrichain.relay

/** Human labels for the integer ids in contracts/crops.json and regions.json. */
object V3Dictionaries {

    val crops: List<Pair<Int, String>> = listOf(
        1 to "Wheat",
        2 to "Rice",
        3 to "Maize",
        4 to "Chilli",
        5 to "Cotton",
        6 to "Turmeric",
        7 to "Groundnut",
        8 to "Soybean",
    )

    val regions: List<Pair<Int, String>> = listOf(
        1 to "Telangana",
        2 to "Andhra Pradesh",
        3 to "Karnataka",
        4 to "Maharashtra",
        5 to "Madhya Pradesh",
        6 to "Gujarat",
        7 to "Rajasthan",
        8 to "Tamil Nadu",
    )

    val grades: List<Pair<Int, String>> = listOf(
        1 to "A",
        2 to "B",
        3 to "C",
        4 to "D",
        5 to "Mixed",
    )

    fun cropName(id: Int): String = crops.firstOrNull { it.first == id }?.second ?: "crop $id"

    fun regionName(id: Int): String = regions.firstOrNull { it.first == id }?.second ?: "region $id"

    fun gradeName(id: Int): String = grades.firstOrNull { it.first == id }?.second ?: "grade $id"

    fun cropId(name: String): Int? =
        crops.firstOrNull { it.second.equals(name, ignoreCase = true) }?.first

    fun regionId(name: String): Int? =
        regions.firstOrNull { it.second.equals(name, ignoreCase = true) }?.first

    fun money(paise: Long): String = "₹%.2f".format(paise / 100.0)
}
