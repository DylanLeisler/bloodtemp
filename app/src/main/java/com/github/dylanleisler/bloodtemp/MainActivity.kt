package com.github.dylanleisler.bloodtemp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.github.dylanleisler.bloodtemp.ui.theme.BloodTempTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private val API_KEY = BuildConfig.OPENWEATHER_API_KEY
private val client = OkHttpClient()

// --- Persistence ---

private const val PREFS_NAME = "bloodtemp_prefs"
private const val KEY_LAT = "cached_lat"
private const val KEY_LON = "cached_lon"
private const val KEY_SAVED_CITIES = "saved_cities"
private const val KEY_HERO_MODE = "hero_mode" // "home" or "last_tapped"

fun saveLastLocation(context: Context, lat: Double, lon: Double) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putLong(KEY_LAT, lat.toBits())
        .putLong(KEY_LON, lon.toBits())
        .apply()
}

fun loadLastLocation(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(KEY_LAT)) return null
    val lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
    val lon = Double.fromBits(prefs.getLong(KEY_LON, 0L))
    return Pair(lat, lon)
}

data class SavedCity(
    val name: String,
    val lat: Double,
    val lon: Double,
    val isHome: Boolean = false,
)

fun saveCities(context: Context, cities: List<SavedCity>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val arr = JSONArray()
    cities.forEach { city ->
        val obj = JSONObject()
        obj.put("name", city.name)
        obj.put("lat", city.lat)
        obj.put("lon", city.lon)
        obj.put("isHome", city.isHome)
        arr.put(obj)
    }
    prefs.edit().putString(KEY_SAVED_CITIES, arr.toString()).apply()
}

fun loadCities(context: Context): List<SavedCity> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_CITIES, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SavedCity(
                name = obj.getString("name"),
                lat = obj.getDouble("lat"),
                lon = obj.getDouble("lon"),
                isHome = obj.optBoolean("isHome", false),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveHeroMode(context: Context, mode: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_HERO_MODE, mode).apply()
}

fun loadHeroMode(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_HERO_MODE, "home") ?: "home"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BloodTempTheme {
                BloodTempScreen()
            }
        }
    }
}

// --- Data classes ---

data class WeatherData(
    val tempKelvin: Double,
    val cityName: String,
    val description: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

data class GeoResult(
    val name: String,
    val state: String?,
    val country: String,
    val lat: Double,
    val lon: Double,
) {
    fun displayLabel(): String {
        val parts = mutableListOf(name)
        if (!state.isNullOrBlank()) parts.add(state)
        parts.add(country)
        return parts.joinToString(", ")
    }
}

// --- API calls ---

suspend fun fetchWeatherByCoords(lat: Double, lon: Double): Result<WeatherData> = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$API_KEY"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))

        if (!response.isSuccessful) {
            val message = JSONObject(body).optString("message", "Unknown error")
            return@withContext Result.failure(Exception(message))
        }

        val json = JSONObject(body)
        val main = json.getJSONObject("main")
        val temp = main.getDouble("temp")
        val name = json.getString("name")
        val coord = json.getJSONObject("coord")
        val weather = json.getJSONArray("weather").getJSONObject(0)
        val desc = weather.getString("description")

        Result.success(WeatherData(temp, name, desc, coord.getDouble("lat"), coord.getDouble("lon")))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun geocodeCity(query: String): Result<List<GeoResult>> = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.openweathermap.org/geo/1.0/direct?q=${query}&limit=5&appid=$API_KEY"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))

        if (!response.isSuccessful) {
            return@withContext Result.failure(Exception("Geocoding failed"))
        }

        val arr = JSONArray(body)
        val results = mutableListOf<GeoResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            results.add(
                GeoResult(
                    name = obj.getString("name"),
                    state = obj.optString("state", null),
                    country = obj.getString("country"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon"),
                )
            )
        }

        if (results.isEmpty()) {
            Result.failure(Exception("No cities found for \"$query\""))
        } else {
            Result.success(results)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// --- UI ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodTempScreen() {
    var cityInput by remember { mutableStateOf("") }
    var currentSearch by remember { mutableStateOf<WeatherData?>(null) }
    var geoResults by remember { mutableStateOf<List<GeoResult>?>(null) }
    var savedCities by remember { mutableStateOf<List<SavedCity>>(emptyList()) }
    var cityWeathers by remember { mutableStateOf<Map<String, WeatherData>>(emptyMap()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var heroMode by remember { mutableStateOf("home") } // "home" or "last_tapped"
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // Hero always shows whatever was last searched/tapped.
    // The toggle only controls what loads on startup.
    val heroDisplay = currentSearch

    // Can we add the current search result to the list?
    val canAddToList = currentSearch != null && savedCities.none {
        it.name.equals(currentSearch!!.cityName, ignoreCase = true)
    }

    // Load persisted state on first launch
    LaunchedEffect(Unit) {
        heroMode = loadHeroMode(context)
        savedCities = loadCities(context)

        // Load startup weather based on hero mode
        if (heroMode == "home") {
            val homeCity = savedCities.find { it.isHome }
            if (homeCity != null) {
                val result = fetchWeatherByCoords(homeCity.lat, homeCity.lon)
                result.onSuccess { currentSearch = it }
            } else {
                val cached = loadLastLocation(context)
                if (cached != null) {
                    val result = fetchWeatherByCoords(cached.first, cached.second)
                    result.onSuccess { currentSearch = it }
                }
            }
        } else {
            val cached = loadLastLocation(context)
            if (cached != null) {
                val result = fetchWeatherByCoords(cached.first, cached.second)
                result.onSuccess { currentSearch = it }
            }
        }

        // Fetch weather for all saved cities
        savedCities.forEach { city ->
            val result = fetchWeatherByCoords(city.lat, city.lon)
            result.onSuccess { weather ->
                val key = "${city.lat},${city.lon}"
                cityWeathers = cityWeathers + (key to weather)
            }
        }
    }

    fun fetchFromLocation() {
        isLocating = true
        errorMessage = null
        geoResults = null
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                CancellationTokenSource().token,
            ).addOnSuccessListener { location ->
                if (location != null) {
                    saveLastLocation(context, location.latitude, location.longitude)
                    scope.launch {
                        val result = fetchWeatherByCoords(location.latitude, location.longitude)
                        result.onSuccess {
                            currentSearch = it
                            errorMessage = null
                        }.onFailure {
                            errorMessage = it.message ?: "Failed to fetch weather"
                        }
                        isLocating = false
                    }
                } else {
                    errorMessage = "Could not determine location"
                    isLocating = false
                }
            }.addOnFailureListener {
                errorMessage = it.message ?: "Location request failed"
                isLocating = false
            }
        } catch (e: SecurityException) {
            errorMessage = "Location permission denied"
            isLocating = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            fetchFromLocation()
        } else {
            errorMessage = "Location permission denied"
        }
    }

    fun doLocationSearch() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fetchFromLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun handleWeatherResult(weather: WeatherData, lat: Double, lon: Double) {
        currentSearch = weather
        saveLastLocation(context, lat, lon)
    }

    fun doCitySearch() {
        if (cityInput.isBlank()) return
        keyboardController?.hide()
        isLoading = true
        errorMessage = null
        geoResults = null
        scope.launch {
            val result = geocodeCity(cityInput.trim())
            result.onSuccess { results ->
                if (results.size == 1) {
                    val geo = results[0]
                    val weatherResult = fetchWeatherByCoords(geo.lat, geo.lon)
                    weatherResult.onSuccess { handleWeatherResult(it, geo.lat, geo.lon) }
                        .onFailure { errorMessage = it.message ?: "Failed to fetch weather" }
                } else {
                    geoResults = results
                }
            }.onFailure {
                errorMessage = it.message ?: "City not found"
            }
            isLoading = false
        }
    }

    fun pickGeoResult(geo: GeoResult) {
        geoResults = null
        isLoading = true
        scope.launch {
            val result = fetchWeatherByCoords(geo.lat, geo.lon)
            result.onSuccess { handleWeatherResult(it, geo.lat, geo.lon) }
                .onFailure { errorMessage = it.message ?: "Failed to fetch weather" }
            isLoading = false
        }
    }

    fun addCurrentToList() {
        val weather = currentSearch ?: return
        val newCity = SavedCity(
            name = weather.cityName,
            lat = weather.lat,
            lon = weather.lon,
            isHome = savedCities.isEmpty(),
        )
        savedCities = savedCities + newCity
        saveCities(context, savedCities)
        val key = "${newCity.lat},${newCity.lon}"
        cityWeathers = cityWeathers + (key to weather)
    }

    fun removeCity(city: SavedCity) {
        val wasHome = city.isHome
        savedCities = savedCities.filter { it != city }
        if (wasHome && savedCities.isNotEmpty()) {
            savedCities = savedCities.mapIndexed { index, c ->
                if (index == 0) c.copy(isHome = true) else c
            }
        }
        saveCities(context, savedCities)
        val key = "${city.lat},${city.lon}"
        cityWeathers = cityWeathers - key
    }

    fun setHome(city: SavedCity) {
        val others = savedCities.filter { it != city }
        val updated = listOf(city.copy(isHome = true)) + others.map { it.copy(isHome = false) }
        savedCities = updated
        saveCities(context, savedCities)
    }

    fun tapCity(city: SavedCity) {
        val key = "${city.lat},${city.lon}"
        val weather = cityWeathers[key] ?: return
        currentSearch = weather
    }

    fun toggleHeroMode() {
        heroMode = if (heroMode == "home") "last_tapped" else "home"
        saveHeroMode(context, heroMode)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Spacer(modifier = Modifier.height(80.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "BLOODTEMP",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 6.sp,
                    )
                }

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "KELVIN OR NOTHING",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 4.sp,
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    label = { Text("Enter city") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doCitySearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { doCitySearch() },
                        enabled = cityInput.isNotBlank() && !isLoading && !isLocating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text("SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = { doLocationSearch() },
                        enabled = !isLoading && !isLocating,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        if (isLocating) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.secondary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text("\uD83D\uDCCD LOCATE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Geo disambiguation
            if (geoResults != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "WHICH ONE?",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 3.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            geoResults!!.forEach { geo ->
                                Text(
                                    text = geo.displayLabel(),
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pickGeoResult(geo) }
                                        .padding(vertical = 10.dp),
                                )
                                if (geo != geoResults!!.last()) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Hero temperature display
            if (heroDisplay != null) {
                item {
                    val data = heroDisplay!!

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(16.dp)
                            )
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = data.cityName.uppercase(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 3.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "%.1f".format(data.tempKelvin),
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                text = "KELVIN",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 8.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = data.description.replaceFirstChar { it.uppercase() },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (canAddToList) {
                        OutlinedButton(
                            onClick = { addCurrentToList() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("+ ADD TO WATCHLIST", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Error
            if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Saved cities section
            if (savedCities.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "WATCHLIST",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 3.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "ON OPEN:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { toggleHeroMode() }) {
                            Text(
                                text = if (heroMode == "home") "\uD83C\uDFE0 HOME" else "\uD83D\uDC41 LAST VIEWED",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(savedCities, key = { "${it.lat},${it.lon}" }) { city ->
                    val key = "${city.lat},${city.lon}"
                    val weather = cityWeathers[key]

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (city.isHome)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.surface,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { tapCity(city) },
                    ) {
                        Box {
                            // "HOME" watermark on home entry
                            if (city.isHome) {
                                Text(
                                    text = "HOME",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    letterSpacing = 6.sp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(end = 48.dp),
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (city.isHome) {
                                            Text(
                                                text = "\uD83C\uDFE0 ",
                                                fontSize = 14.sp,
                                            )
                                        }
                                        Text(
                                            text = city.name.uppercase(),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            letterSpacing = 2.sp,
                                        )
                                    }
                                    if (weather != null) {
                                        Text(
                                            text = weather.description.replaceFirstChar { it.uppercase() },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (weather != null) {
                                    Text(
                                        text = "%.1f K".format(weather.tempKelvin),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Context menu
                                var menuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    Text(
                                        text = "\u22EE",
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clickable { menuExpanded = true }
                                            .padding(horizontal = 8.dp),
                                    )
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        if (!city.isHome) {
                                            DropdownMenuItem(
                                                text = { Text("Set as Home") },
                                                onClick = {
                                                    setHome(city)
                                                    menuExpanded = false
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Remove",
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            },
                                            onClick = {
                                                removeCity(city)
                                                menuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
