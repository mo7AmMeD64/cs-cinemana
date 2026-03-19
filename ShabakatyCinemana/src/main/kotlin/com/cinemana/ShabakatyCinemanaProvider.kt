package com.cinemana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.serialization.json.*
import java.util.Calendar

class ShabakatyCinemanaProvider : MainAPI() {

    override var name = "Shabakaty Cinemana(by-mohammed)"
    override var mainUrl = "https://cinemana.shabakaty.cc"
    private val apiUrl = "$mainUrl/api/android"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    companion object {
        private const val POPULAR_ITEMS_PER_PAGE = 30
        private const val SEARCH_ITEMS_PER_PAGE = 12
        private const val LATEST_ITEMS_PER_PAGE = 24
        private const val SUBTITLE_DELIMITER = " - "
    }

    // ─── JSON helpers ─────────────────────────────────────────────────────────

    private fun String.toJsonArray(): JsonArray? = try {
        if (isBlank() || this == "[]") null
        else Json.parseToJsonElement(this).jsonArray
    } catch (e: Exception) { null }

    private fun String.toJsonObject(): JsonObject? = try {
        if (isBlank()) null
        else Json.parseToJsonElement(this).jsonObject
    } catch (e: Exception) { null }

    // ─── Item parser ──────────────────────────────────────────────────────────

    private fun JsonObject.toSearchResponse(): SearchResponse? {
        val nb = this["nb"]?.jsonPrimitive?.content ?: return null
        val enTitle = this["en_title"]?.jsonPrimitive?.content ?: "No Title"
        val imgObjUrl = this["imgObjUrl"]?.jsonPrimitive?.content
        val kind = this["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

        return if (kind == 2) {
            newTvSeriesSearchResponse(enTitle, nb, TvType.TvSeries) {
                this.posterUrl = imgObjUrl
            }
        } else {
            newMovieSearchResponse(enTitle, nb, TvType.Movie) {
                this.posterUrl = imgObjUrl
            }
        }
    }

    private fun JsonArray.toSearchList(): List<SearchResponse> =
        mapNotNull { it.jsonObject.toSearchResponse() }

    // ─── Main Page ────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$apiUrl/latestMovies/level/0/itemsPerPage/$LATEST_ITEMS_PER_PAGE/page/" to "أحدث الأفلام",
        "$apiUrl/latestSeries/level/0/itemsPerPage/$LATEST_ITEMS_PER_PAGE/page/" to "أحدث المسلسلات",
        "$apiUrl/video/V/2/itemsPerPage/$POPULAR_ITEMS_PER_PAGE/level/0/sortParam/desc/pageNumber/" to "الأكثر مشاهدة",
        "$apiUrl/video/V/2/itemsPerPage/$POPULAR_ITEMS_PER_PAGE/level/0/videoKind/1/sortParam/desc/pageNumber/" to "أكثر الأفلام مشاهدة",
        "$apiUrl/video/V/2/itemsPerPage/$POPULAR_ITEMS_PER_PAGE/level/0/videoKind/2/sortParam/desc/pageNumber/" to "أكثر المسلسلات مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}${page - 1}"
        val items = app.get(url).text.toJsonArray()?.toSearchList() ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        val year = "1900,${Calendar.getInstance().get(Calendar.YEAR)}"

        fun buildUrl(type: String, page: Int): String {
            val base = "$apiUrl/AdvancedSearch?level=0&page=$page&year=$year&type=$type"
            return if (trimmed.isNotBlank()) "$base&videoTitle=$trimmed" else base
        }

        val results = mutableListOf<SearchResponse>()
        for (page in 0..2) {
            val movies = app.get(buildUrl("movies", page)).text.toJsonArray()?.toSearchList() ?: emptyList()
            val series = app.get(buildUrl("series", page)).text.toJsonArray()?.toSearchList() ?: emptyList()
            results.addAll(movies + series)
            if (movies.size < SEARCH_ITEMS_PER_PAGE && series.size < SEARCH_ITEMS_PER_PAGE) break
        }
        return results
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        // nb من نتائج البحث مباشرة - نفس منطق أنيومي anime.url
        val nb = url.substringAfterLast("/")

        val info = app.get("$apiUrl/allVideoInfo/id/$nb").text.toJsonObject() ?: return null

        val title = info["en_title"]?.jsonPrimitive?.content ?: return null
        val posterUrl = info["imgObjUrl"]?.jsonPrimitive?.content
        val year = info["year"]?.jsonPrimitive?.content?.toIntOrNull()
        val stars = info["stars"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: 0
        val starsText = "★".repeat(stars / 2) + "☆".repeat(5 - (stars / 2))
        val likes = info["Likes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val dislikes = info["DisLikes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val enContent = info["en_content"]?.jsonPrimitive?.content

        val plot = listOfNotNull(
            "${year ?: "N/A"} | $starsText | 👍$likes  👎$dislikes",
            enContent
        ).joinToString("\n\n")

        val categories = info["categories"]?.jsonArray?.mapNotNull {
            it.jsonObject["en_title"]?.jsonPrimitive?.content
        } ?: emptyList()
        val language = info["videoLanguages"]?.jsonObject?.get("en_title")?.jsonPrimitive?.content
        val tags = categories + listOfNotNull(language)

        val actorsList = info["actorsInfo"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }?.map { ActorData(Actor(it)) }

        // ✅ نفس منطق أنيومي: episodeListRequest يستخدم anime.url مباشرة
        val episodesRaw = app.get("$apiUrl/videoSeason/id/$nb").text.toJsonArray()

        // ✅ نفس منطق أنيومي: إذا فارغ = فيلم، وإلا = مسلسل
        return if (episodesRaw.isNullOrEmpty()) {
            // ✅ فيلم: data = nb مباشرة - نفس أنيومي episode.url = anime.url
            newMovieLoadResponse(title, nb, TvType.Movie, nb) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actorsList
            }
        } else {
            val seasonsMap = mutableMapOf<Int, MutableList<Episode>>()
            episodesRaw.forEach { elem ->
                val ep = elem.jsonObject
                // ✅ نفس أنيومي: url = nb الحلقة من videoSeason
                val epNb = ep["nb"]?.jsonPrimitive?.content ?: return@forEach
                val epNum = ep["episodeNummer"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val sNum = ep["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                // ✅ epNb يُمرر كـ data لـ loadLinks - نفس أنيومي episode.url = nb
                val episode = newEpisode(epNb) {
                    this.name = "الموسم $sNum - الحلقة $epNum"
                    this.season = sNum
                    this.episode = epNum
                }
                seasonsMap.getOrPut(sNum) { mutableListOf() }.add(episode)
            }

            val episodes = seasonsMap.keys.sorted()
                .flatMap { s -> seasonsMap[s]!!.sortedBy { it.episode } }
                .reversed()

            newTvSeriesLoadResponse(title, nb, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                this.actors = actorsList
            }
        }
    }

    // ─── Load Links ───────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ✅ نفس أنيومي: episode.url = nb الحلقة من videoSeason
        // يُستخدم مباشرة في videoListRequest و translationFiles
        val nb = data.trim()

        // ─── Subtitles - نفس أنيومي translationFiles/id/episode.url ──────────
        try {
            app.get("$apiUrl/translationFiles/id/$nb").text.toJsonObject()
                ?.get("translations")?.jsonArray?.forEach { elem ->
                    val sub = elem.jsonObject
                    val file = sub["file"]?.jsonPrimitive?.content ?: return@forEach
                    val subName = sub["name"]?.jsonPrimitive?.content
                    val ext = sub["extention"]?.jsonPrimitive?.content
                    val lang = listOfNotNull(subName, ext).joinToString(SUBTITLE_DELIMITER)
                    subtitleCallback(SubtitleFile(lang, file))
                }
        } catch (_: Exception) {}

        // ─── Videos - نفس أنيومي transcoddedFiles/id/episode.url ────────────
        val videosJson = app.get("$apiUrl/transcoddedFiles/id/$nb").text.toJsonArray()
            ?: return false

        videosJson.forEach { elem ->
            val video = elem.jsonObject
            val videoUrl = video["videoUrl"]?.jsonPrimitive?.content ?: return@forEach
            val resolution = video["resolution"]?.jsonPrimitive?.content ?: ""
            val headers = mapOf("Referer" to mainUrl)
            callback(
                newExtractorLink(
                    source = name,
                    name = resolution.ifBlank { "Default" },
                    url = videoUrl,
                ) {
                    this.headers = headers
                    this.quality = getQualityFromName(resolution)
                }
            )
        }

        return videosJson.isNotEmpty()
    }
}
