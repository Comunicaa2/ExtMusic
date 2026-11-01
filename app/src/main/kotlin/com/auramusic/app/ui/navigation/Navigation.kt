package com.auramusic.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import com.auramusic.app.ui.theme.Movimiento
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.auramusic.app.ui.screens.*

sealed class Screen(val route: String) {
    object Home       : Screen("home")
    object Search     : Screen("search")
    object Library    : Screen("library")
    object Artists    : Screen("artists")
    object Artist     : Screen("artist/{artistId}?browseId={browseId}") {
        // El id es el NOMBRE del artista: puede traer espacios o caracteres especiales
        // (/, &, #, ?…). Hay que URL-encodearlo o la ruta no empareja y la pantalla
        // queda en blanco. Navigation decodifica el argumento al leerlo con getString.
        // browseId (opcional) es el id de su página en YT Music, si ya se conoce.
        fun createRoute(id: String, browseId: String? = null) = "artist/${android.net.Uri.encode(id)}" +
            (browseId?.takeIf { it.isNotBlank() }?.let { "?browseId=${android.net.Uri.encode(it)}" } ?: "")
    }
    object Coleccion  : Screen("coleccion/{browseId}") {   // álbum o playlist de YT Music
        fun createRoute(browseId: String) = "coleccion/${android.net.Uri.encode(browseId)}"
    }
    object NowPlaying : Screen("now_playing")
    object Downloads  : Screen("downloads")
    object Settings   : Screen("settings")
    object Local      : Screen("local")   // música del dispositivo (Fase 1)
    object PlaylistDetail : Screen("playlist/{playlistId}") {   // Mejora 9
        fun createRoute(id: String) = "playlist/${android.net.Uri.encode(id)}"
        // Playlists automáticas: no se guardan, se calculan de las canciones con
        // me gusta / descargadas (los ids reales son UUID, no chocan).
        const val ME_GUSTA  = "auto:megusta"
        const val DESCARGAS = "auto:descargas"
    }
}

@Composable
fun AuraNavHost(navController: NavHostController, playerViewModel: PlayerViewModel) {
    val entrePestanas = { a: NavBackStackEntry, b: NavBackStackEntry ->
        a.destination.route in PESTANAS && b.destination.route in PESTANAS }
    NavHost(
        navController = navController, startDestination = Screen.Home.route,
        // Entre pestañas, "fade through" (solo fundido y un leve crecimiento). Al
        // entrar en un detalle (artista, colección, playlist, ajustes…), eje X
        // compartido: la nueva entra desde la derecha 1/10 y la otra se va a la
        // izquierda; al volver, al revés (también siguiendo el gesto de atrás).
        // El reproductor no es un destino: es una capa encima (MainActivity).
        enterTransition = {
            if (entrePestanas(initialState, targetState)) entradaPestana() else entradaDetalle(1)
        },
        exitTransition = {
            if (entrePestanas(initialState, targetState)) salidaPestana() else salidaDetalle(-1)
        },
        popEnterTransition = {
            if (entrePestanas(initialState, targetState)) entradaPestana() else entradaDetalle(-1)
        },
        popExitTransition = {
            if (entrePestanas(initialState, targetState)) salidaPestana() else salidaDetalle(1)
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController, playerViewModel)
        }
        composable(Screen.Search.route) {
            SearchScreen(navController, playerViewModel)
        }
        composable(Screen.Library.route) {
            LibraryScreen(navController, playerViewModel)
        }
        composable(Screen.Artists.route) {
            ArtistsScreen(navController, playerViewModel)
        }
        composable(
            Screen.Artist.route,
            arguments = listOf(navArgument("browseId") { type = NavType.StringType; nullable = true; defaultValue = null })
        ) { back ->
            val id = back.arguments?.getString("artistId") ?: return@composable
            ArtistScreen(id, back.arguments?.getString("browseId"), navController, playerViewModel)
        }
        composable(Screen.Coleccion.route) { back ->
            val id = back.arguments?.getString("browseId") ?: return@composable
            ColeccionScreen(id, navController, playerViewModel)
        }
        composable(Screen.Downloads.route) {
            DownloadsScreen(navController, playerViewModel)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController)
        }
        composable(Screen.Local.route) {
            CancionesLocalesScreen(navController, playerViewModel)
        }
        composable(Screen.PlaylistDetail.route) { back ->
            val id = back.arguments?.getString("playlistId") ?: return@composable
            PlaylistDetailScreen(id, navController, playerViewModel)
        }
    }
}

// Pestañas de la barra inferior: entre ellas no hay "dirección", solo fundido.
private val PESTANAS = setOf(Screen.Home.route, Screen.Search.route, Screen.Local.route, Screen.Library.route)

// Fade through de Material: la saliente se apaga en 90 ms y la entrante empieza a
// aparecer a los 60 con una curva que arranca rápido (con fundido lineal tras los
// 90 ms quedaba un instante la pantalla casi vacía: parecía un parpadeo).
private fun entradaPestana(): EnterTransition =
    fadeIn(tween(Movimiento.MEDIA - ESPERA_ENTRADA, delayMillis = ESPERA_ENTRADA, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(Movimiento.MEDIA - ESPERA_ENTRADA, delayMillis = ESPERA_ENTRADA, easing = Movimiento.Entrada),
            initialScale = 0.96f)
private fun salidaPestana(): ExitTransition = fadeOut(tween(SALIDA_RAPIDA, easing = FastOutLinearInEasing))

// Eje X compartido de Material: las dos se deslizan juntas con la misma curva, pero
// la saliente se apaga en los primeros 90 ms y la entrante aparece después, así nunca
// se ven las dos superpuestas (se mezclaban los títulos). [lado] 1 = derecha, -1 = izquierda.
private fun entradaDetalle(lado: Int): EnterTransition =
    slideInHorizontally(Movimiento.estandar(Movimiento.MEDIA)) { it * lado / 10 } +
        fadeIn(tween(Movimiento.MEDIA - SALIDA_RAPIDA, delayMillis = SALIDA_RAPIDA, easing = LinearOutSlowInEasing))
private fun salidaDetalle(lado: Int): ExitTransition =
    slideOutHorizontally(Movimiento.estandar(Movimiento.MEDIA)) { it * lado / 10 } +
        fadeOut(tween(SALIDA_RAPIDA, easing = FastOutLinearInEasing))

private const val SALIDA_RAPIDA = 90
private const val ESPERA_ENTRADA = 60
