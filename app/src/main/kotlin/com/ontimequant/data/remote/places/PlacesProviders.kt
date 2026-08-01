package com.ontimequant.data.remote.places

import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.LocationKind
import com.ontimequant.model.PlaceSuggestion
import com.ontimequant.model.PlacesProvider
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Places API (New) DTOs
// ---------------------------------------------------------------------------

@Serializable
data class AutocompleteRequest(
    val input: String,
    val locationBias: LocationBias? = null,
)

@Serializable
data class LocationBias(val circle: BiasCircle)

@Serializable
data class BiasCircle(val center: PlaceLatLng, val radius: Double)

@Serializable
data class PlaceLatLng(val latitude: Double, val longitude: Double)

@Serializable
data class AutocompleteResponse(val suggestions: List<Suggestion> = emptyList())

@Serializable
data class Suggestion(val placePrediction: PlacePrediction? = null)

@Serializable
data class PlacePrediction(
    val placeId: String,
    val structuredFormat: StructuredFormat? = null,
    val text: TextField? = null,
)

@Serializable
data class StructuredFormat(val mainText: TextField? = null, val secondaryText: TextField? = null)

@Serializable
data class TextField(val text: String = "")

@Serializable
data class PlaceDetails(
    val id: String = "",
    val formattedAddress: String = "",
    val location: PlaceLatLng? = null,
    @SerialName("displayName") val displayName: TextField? = null,
)

interface PlacesApi {
    @POST("v1/places:autocomplete")
    suspend fun autocomplete(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Body body: AutocompleteRequest,
    ): AutocompleteResponse

    @GET("v1/places/{placeId}")
    suspend fun details(
        @Path("placeId") placeId: String,
        @Header("X-Goog-Api-Key") apiKey: String,
        @Header("X-Goog-FieldMask") fieldMask: String = "id,formattedAddress,location,displayName",
        @Query("languageCode") language: String = "en",
    ): PlaceDetails
}

class GooglePlacesProvider @Inject constructor(
    private val api: PlacesApi,
    private val apiKey: String,
) : PlacesProvider {

    override val name: String = "Google Places"

    override suspend fun autocomplete(query: String, near: GeoPoint?): ProviderResult<List<PlaceSuggestion>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext missingKey()
            if (query.length < 3) return@withContext ProviderResult.Success(emptyList(), DataProvenance.LIVE)
            runCatching {
                api.autocomplete(
                    apiKey = apiKey,
                    body = AutocompleteRequest(
                        input = query,
                        locationBias = near?.let {
                            LocationBias(BiasCircle(PlaceLatLng(it.latitude, it.longitude), 30_000.0))
                        },
                    ),
                )
            }.fold(
                onSuccess = { response ->
                    ProviderResult.Success(
                        response.suggestions.mapNotNull { it.placePrediction }.map { p ->
                            PlaceSuggestion(
                                providerPlaceId = p.placeId,
                                primaryText = p.structuredFormat?.mainText?.text
                                    ?: p.text?.text.orEmpty(),
                                secondaryText = p.structuredFormat?.secondaryText?.text.orEmpty(),
                            )
                        },
                        DataProvenance.LIVE,
                    )
                },
                onFailure = { e ->
                    ProviderResult.Failure(
                        ProviderNotice(
                            name, DataProvenance.UNAVAILABLE,
                            "Address search is unavailable. You can still enter a destination by name.",
                        ),
                        e,
                    )
                },
            )
        }

    override suspend fun resolve(suggestion: PlaceSuggestion): ProviderResult<SavedLocation> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext missingKey()
            runCatching { api.details(suggestion.providerPlaceId, apiKey) }.fold(
                onSuccess = { details ->
                    val location = details.location
                        ?: return@fold ProviderResult.Failure(
                            ProviderNotice(name, DataProvenance.UNAVAILABLE, "That place has no coordinates."),
                        )
                    ProviderResult.Success(
                        SavedLocation(
                            id = UUID.randomUUID().toString(),
                            label = details.displayName?.text?.takeIf { it.isNotBlank() }
                                ?: suggestion.primaryText,
                            address = details.formattedAddress.takeIf { it.isNotBlank() }
                                ?: suggestion.secondaryText,
                            point = GeoPoint(location.latitude, location.longitude),
                            providerPlaceId = details.id.takeIf { it.isNotBlank() },
                        ),
                        DataProvenance.LIVE,
                    )
                },
                onFailure = { e ->
                    ProviderResult.Failure(
                        ProviderNotice(name, DataProvenance.UNAVAILABLE, "Could not look up that address."),
                        e,
                    )
                },
            )
        }

    private fun <T> missingKey(): ProviderResult<T> = ProviderResult.Failure(
        ProviderNotice(name, DataProvenance.UNAVAILABLE, "No Places API key is configured."),
    )
}

/**
 * Offline place search over a small fixed gazetteer of Abu Dhabi locations plus anything
 * the user has already saved. Enough for the demo to be genuinely usable — you can search,
 * pick a destination and get a forecast without a key.
 */
class DemoPlacesProvider @Inject constructor() : PlacesProvider {

    override val name: String = "Demo places"

    private val gazetteer: List<SavedLocation> = listOf(
        DemoScenario.HOME,
        DemoScenario.OFFICE,
        DemoScenario.AIRPORT,
        SavedLocation(
            "demo-place-adnec", "ADNEC Centre Abu Dhabi",
            "Khaleej Al Arabi Street, Al Khaleej Al Arabi", GeoPoint(24.4180, 54.4370),
        ),
        SavedLocation(
            "demo-place-galleria", "The Galleria Al Maryah Island",
            "Al Maryah Island, Abu Dhabi", GeoPoint(24.5005, 54.3878),
        ),
        SavedLocation(
            "demo-place-corniche", "Corniche Beach",
            "Corniche Road, Al Khubeirah", GeoPoint(24.4750, 54.3300),
        ),
        SavedLocation(
            "demo-place-mosque", "Sheikh Zayed Grand Mosque",
            "Sheikh Rashid Bin Saeed Street", GeoPoint(24.4128, 54.4750),
        ),
        SavedLocation(
            "demo-place-cleveland", "Cleveland Clinic Abu Dhabi",
            "Al Maryah Island", GeoPoint(24.5010, 54.3846),
        ),
        SavedLocation(
            "demo-place-school", "Brighton College Abu Dhabi",
            "Bloom Gardens, Al Matar", GeoPoint(24.4360, 54.4142), LocationKind.SCHOOL,
        ),
        SavedLocation(
            "demo-place-yas", "Yas Marina Circuit",
            "Yas Island, Abu Dhabi", GeoPoint(24.4672, 54.6031),
        ),
    )

    override suspend fun autocomplete(query: String, near: GeoPoint?): ProviderResult<List<PlaceSuggestion>> {
        val needle = query.trim().lowercase()
        val matches = if (needle.length < 2) gazetteer else gazetteer.filter {
            it.label.lowercase().contains(needle) || it.address.lowercase().contains(needle)
        }
        return ProviderResult.Success(
            matches.map { PlaceSuggestion(it.id, it.label, it.address) },
            DataProvenance.DEMO,
        )
    }

    override suspend fun resolve(suggestion: PlaceSuggestion): ProviderResult<SavedLocation> {
        val match = gazetteer.firstOrNull { it.id == suggestion.providerPlaceId }
            ?: return ProviderResult.Failure(
                ProviderNotice(name, DataProvenance.DEMO, "That demo place no longer exists."),
            )
        return ProviderResult.Success(
            match.copy(id = UUID.randomUUID().toString(), providerPlaceId = match.id),
            DataProvenance.DEMO,
        )
    }
}
