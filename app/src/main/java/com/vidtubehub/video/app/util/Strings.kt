package com.vidtubehub.video.app.util

import com.vidtubehub.video.app.data.Settings
import java.util.Locale

private val es = mapOf(
    "Home" to "Inicio", "History" to "Historial", "Downloads" to "Descargas", "Settings" to "Ajustes", "Trending" to "Tendencias",
    "Search videos" to "Buscar videos", "Go" to "Ir", "Retry" to "Reintentar", "All" to "Todo", "Music" to "Música", "Gaming" to "Juegos",
    "Travel" to "Viajes", "Food" to "Comida", "Tech" to "Tecnología", "News" to "Noticias", "Up next" to "A continuación",
    "You're offline — showing saved videos" to "Sin conexión: mostrando videos guardados", "You've reached the end" to "Has llegado al final",
    "Offline — that's all that's saved" to "Sin conexión: eso es todo lo guardado", "Couldn't load more — tap to retry" to "No se pudo cargar más: toca para reintentar",
    "Couldn't load videos. Check your connection." to "No se pudieron cargar los videos. Revisa tu conexión.", "No public videos found." to "No se encontraron videos públicos.",
    "Theme" to "Tema", "System" to "Sistema", "Dark" to "Oscuro", "Light" to "Claro", "Language" to "Idioma", "Download over Wi-Fi only" to "Descargar solo con Wi-Fi",
    "Autoplay next video" to "Reproducir siguiente automáticamente", "Playback quality" to "Calidad de reproducción", "Auto (fast start)" to "Auto (inicio rápido)",
    "Cache" to "Caché", "Clear video + list cache" to "Borrar caché de videos y listas", "cleared" to "borrado", "Close" to "Cerrar", "Download" to "Descargar",
    "Video" to "Video", "Audio (MP3)" to "Audio (MP3)", "Quality" to "Calidad", "Connect to the internet to see qualities." to "Conéctate a internet para ver las calidades.",
    "Tap the download icon on a video to save video or audio." to "Toca el ícono de descarga en un video para guardar video o audio.",
    "Download started — see Downloads tab" to "Descarga iniciada: mira la pestaña Descargas",
    "Saved" to "Guardado", "Preparing on server…" to "Preparando en el servidor…", "Paused — will resume when online" to "En pausa: se reanudará al haber conexión",
    "Reconnecting…" to "Reconectando…", "Failed" to "Error", "Queued" to "En cola", "Waiting for network" to "Esperando red",
    "Your watch history will appear here." to "Tu historial de reproducción aparecerá aquí.", "Clear all" to "Borrar todo", "Resume" to "Continuar",
    "Loading…" to "Cargando…", "Waiting for connection…" to "Esperando conexión…", "Connection lost — resuming when back online…" to "Conexión perdida: se reanudará al volver",
    "Playback error" to "Error de reproducción", "Preparing" to "Preparando", "Downloaded" to "Descargado"
)
private val fr = mapOf(
    "Home" to "Accueil", "History" to "Historique", "Downloads" to "Téléchargements", "Settings" to "Réglages", "Trending" to "Tendances",
    "Search videos" to "Rechercher des vidéos", "Go" to "OK", "Retry" to "Réessayer", "All" to "Tout", "Music" to "Musique", "Gaming" to "Jeux",
    "Travel" to "Voyage", "Food" to "Cuisine", "Tech" to "Tech", "News" to "Actualités", "Up next" to "À suivre",
    "You're offline — showing saved videos" to "Hors ligne — vidéos enregistrées affichées", "You've reached the end" to "Vous êtes à la fin",
    "Offline — that's all that's saved" to "Hors ligne — c'est tout ce qui est enregistré", "Couldn't load more — tap to retry" to "Chargement impossible — touchez pour réessayer",
    "Couldn't load videos. Check your connection." to "Impossible de charger les vidéos. Vérifiez votre connexion.", "No public videos found." to "Aucune vidéo publique trouvée.",
    "Theme" to "Thème", "System" to "Système", "Dark" to "Sombre", "Light" to "Clair", "Language" to "Langue", "Download over Wi-Fi only" to "Télécharger uniquement en Wi-Fi",
    "Autoplay next video" to "Lecture auto de la suivante", "Playback quality" to "Qualité de lecture", "Auto (fast start)" to "Auto (démarrage rapide)",
    "Cache" to "Cache", "Clear video + list cache" to "Vider le cache vidéos et listes", "cleared" to "vidé", "Close" to "Fermer", "Download" to "Télécharger",
    "Video" to "Vidéo", "Audio (MP3)" to "Audio (MP3)", "Quality" to "Qualité", "Connect to the internet to see qualities." to "Connectez-vous à Internet pour voir les qualités.",
    "Tap the download icon on a video to save video or audio." to "Touchez l'icône de téléchargement d'une vidéo pour l'enregistrer.",
    "Download started — see Downloads tab" to "Téléchargement lancé — voir l'onglet Téléchargements",
    "Saved" to "Enregistré", "Preparing on server…" to "Préparation sur le serveur…", "Paused — will resume when online" to "En pause — reprise dès le retour de la connexion",
    "Reconnecting…" to "Reconnexion…", "Failed" to "Échec", "Queued" to "En attente", "Waiting for network" to "En attente du réseau",
    "Your watch history will appear here." to "Votre historique apparaîtra ici.", "Clear all" to "Tout effacer", "Resume" to "Reprendre",
    "Loading…" to "Chargement…", "Waiting for connection…" to "En attente de connexion…", "Connection lost — resuming when back online…" to "Connexion perdue — reprise au retour",
    "Playback error" to "Erreur de lecture", "Preparing" to "Préparation", "Downloaded" to "Téléchargé"
)
private val de = mapOf(
    "Home" to "Start", "History" to "Verlauf", "Downloads" to "Downloads", "Settings" to "Einstellungen", "Trending" to "Trends",
    "Search videos" to "Videos suchen", "Go" to "Los", "Retry" to "Erneut versuchen", "All" to "Alle", "Music" to "Musik", "Gaming" to "Gaming",
    "Travel" to "Reisen", "Food" to "Essen", "Tech" to "Technik", "News" to "Nachrichten", "Up next" to "Als Nächstes",
    "You're offline — showing saved videos" to "Offline — gespeicherte Videos", "You've reached the end" to "Ende erreicht",
    "Offline — that's all that's saved" to "Offline — mehr ist nicht gespeichert", "Couldn't load more — tap to retry" to "Laden fehlgeschlagen — tippen zum Wiederholen",
    "Couldn't load videos. Check your connection." to "Videos konnten nicht geladen werden. Verbindung prüfen.", "No public videos found." to "Keine öffentlichen Videos gefunden.",
    "Theme" to "Design", "System" to "System", "Dark" to "Dunkel", "Light" to "Hell", "Language" to "Sprache", "Download over Wi-Fi only" to "Nur über WLAN herunterladen",
    "Autoplay next video" to "Nächstes Video automatisch", "Playback quality" to "Wiedergabequalität", "Auto (fast start)" to "Auto (Schnellstart)",
    "Cache" to "Cache", "Clear video + list cache" to "Video- und Listen-Cache leeren", "cleared" to "geleert", "Close" to "Schließen", "Download" to "Herunterladen",
    "Video" to "Video", "Audio (MP3)" to "Audio (MP3)", "Quality" to "Qualität", "Connect to the internet to see qualities." to "Mit dem Internet verbinden, um Qualitäten zu sehen.",
    "Tap the download icon on a video to save video or audio." to "Tippe bei einem Video auf das Download-Symbol.",
    "Download started — see Downloads tab" to "Download gestartet — siehe Tab Downloads",
    "Saved" to "Gespeichert", "Preparing on server…" to "Wird auf dem Server vorbereitet…", "Paused — will resume when online" to "Pausiert — setzt online fort",
    "Reconnecting…" to "Verbinde neu…", "Failed" to "Fehlgeschlagen", "Queued" to "In Warteschlange", "Waiting for network" to "Warte auf Netzwerk",
    "Your watch history will appear here." to "Dein Verlauf erscheint hier.", "Clear all" to "Alle löschen", "Resume" to "Fortsetzen",
    "Loading…" to "Lädt…", "Waiting for connection…" to "Warte auf Verbindung…", "Connection lost — resuming when back online…" to "Verbindung verloren — setzt fort, sobald online",
    "Playback error" to "Wiedergabefehler", "Preparing" to "Vorbereitung", "Downloaded" to "Heruntergeladen"
)
private val pt = mapOf(
    "Home" to "Início", "History" to "Histórico", "Downloads" to "Downloads", "Settings" to "Configurações", "Trending" to "Em alta",
    "Search videos" to "Pesquisar vídeos", "Go" to "Ir", "Retry" to "Tentar de novo", "All" to "Tudo", "Music" to "Música", "Gaming" to "Jogos",
    "Travel" to "Viagem", "Food" to "Comida", "Tech" to "Tecnologia", "News" to "Notícias", "Up next" to "A seguir",
    "You're offline — showing saved videos" to "Sem conexão — mostrando vídeos salvos", "You've reached the end" to "Você chegou ao fim",
    "Offline — that's all that's saved" to "Sem conexão — só isso está salvo", "Couldn't load more — tap to retry" to "Falha ao carregar — toque para tentar de novo",
    "Couldn't load videos. Check your connection." to "Não foi possível carregar os vídeos. Verifique sua conexão.", "No public videos found." to "Nenhum vídeo público encontrado.",
    "Theme" to "Tema", "System" to "Sistema", "Dark" to "Escuro", "Light" to "Claro", "Language" to "Idioma", "Download over Wi-Fi only" to "Baixar somente no Wi-Fi",
    "Autoplay next video" to "Reproduzir próximo automaticamente", "Playback quality" to "Qualidade de reprodução", "Auto (fast start)" to "Auto (início rápido)",
    "Cache" to "Cache", "Clear video + list cache" to "Limpar cache de vídeos e listas", "cleared" to "limpo", "Close" to "Fechar", "Download" to "Baixar",
    "Video" to "Vídeo", "Audio (MP3)" to "Áudio (MP3)", "Quality" to "Qualidade", "Connect to the internet to see qualities." to "Conecte-se à internet para ver as qualidades.",
    "Tap the download icon on a video to save video or audio." to "Toque no ícone de download em um vídeo para salvar.",
    "Download started — see Downloads tab" to "Download iniciado — veja a aba Downloads",
    "Saved" to "Salvo", "Preparing on server…" to "Preparando no servidor…", "Paused — will resume when online" to "Pausado — continua ao voltar a conexão",
    "Reconnecting…" to "Reconectando…", "Failed" to "Falhou", "Queued" to "Na fila", "Waiting for network" to "Aguardando rede",
    "Your watch history will appear here." to "Seu histórico aparecerá aqui.", "Clear all" to "Limpar tudo", "Resume" to "Continuar",
    "Loading…" to "Carregando…", "Waiting for connection…" to "Aguardando conexão…", "Connection lost — resuming when back online…" to "Conexão perdida — continua ao voltar",
    "Playback error" to "Erro de reprodução", "Preparing" to "Preparando", "Downloaded" to "Baixado"
)
private val tables = mapOf("es" to es, "fr" to fr, "de" to de, "pt" to pt)
val LANGUAGES = listOf("System", "English", "Español", "Français", "Deutsch", "Português")
private val codes = mapOf("English" to "en", "Español" to "es", "Français" to "fr", "Deutsch" to "de", "Português" to "pt")

/** Reads Settings.language (Compose state) so every tr() call recomposes when the language changes. */
fun tr(en: String): String {
    val code = codes[Settings.language] ?: Locale.getDefault().language
    return tables[code]?.get(en) ?: en
}
