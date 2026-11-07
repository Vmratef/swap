package com.lanshare.app

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.TypedValue
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
	private lateinit var prefs: SharedPreferences

	private lateinit var tvUrl: TextView
	private lateinit var tvSubtitle: TextView
	private lateinit var tvStatus: TextView
	private lateinit var tvQrMode: TextView
	private lateinit var qrImage: ImageView

	private var currentUrl = ""
	private var currentTreeUri: String? = null
	private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var collectJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
		currentTreeUri = prefs.getString(AppPrefs.KEY_FOLDER_URI, null)

		tvUrl = findViewById(R.id.tvUrl)
		tvSubtitle = findViewById(R.id.tvSubtitle)
		tvStatus = findViewById(R.id.tvStatus)
		tvQrMode = findViewById(R.id.tvQrMode)
		qrImage = findViewById(R.id.ivQr)

		val btnOpenBrowser: Button = findViewById(R.id.btnOpenBrowser)
		val btnStartServer: Button = findViewById(R.id.btnStartServer)
		val btnStopServer: Button = findViewById(R.id.btnStopServer)
		val btnSettings: Button = findViewById(R.id.btnSettings)
		val btnAddFiles: Button = findViewById(R.id.btnAddFiles)

		tvSubtitle.text = getString(R.string.subtitle)

		val storage = StorageAccess(this, currentTreeUri)
		storage.ensureRootExists()
		tvUrl.text = getString(R.string.server_not_running)
		tvQrMode.text = getString(R.string.qr_mode_none)
		renderQr("")
		handleShareIntent(intent)

		btnOpenBrowser.setOnClickListener {
			if (currentUrl.isBlank()) {
				toast("Server URL not ready")
				return@setOnClickListener
			}
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
		}

		btnStartServer.setOnClickListener {
			if (!ensureFolderSelected()) return@setOnClickListener
			tvQrMode.text = getString(R.string.qr_mode_server)
			startOrRestartService()
		}

		btnStopServer.setOnClickListener {
			val stopIntent = Intent(this, ServerService::class.java).apply {
				action = ServerService.ACTION_STOP
			}
			runCatching { startService(stopIntent) }
			currentUrl = ""
			tvUrl.text = getString(R.string.server_not_running)
			tvStatus.text = "Status: stopped"
			tvStatus.setTextColor(resources.getColor(R.color.status_stopped, theme))
			tvQrMode.text = getString(R.string.qr_mode_none)
			renderQr("")
		}

		btnAddFiles.setOnClickListener {
			if (!ensureFolderSelected()) return@setOnClickListener
			val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
				addCategory(Intent.CATEGORY_OPENABLE)
				type = "*/*"
				putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
			}
			startActivityForResult(pick, REQ_PICK_FILES)
		}

		btnSettings.setOnClickListener {
			startActivity(Intent(this, SettingsActivity::class.java))
		}

		AppTheme.apply(this)
	}

	override fun onResume() {
		super.onResume()
		// Settings may have changed the folder or theme while we were paused.
		currentTreeUri = prefs.getString(AppPrefs.KEY_FOLDER_URI, null)
		AppTheme.apply(this)
		observeServerState()
	}

	override fun onNewIntent(intent: Intent?) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleShareIntent(intent)
	}

	override fun onPause() {
		collectJob?.cancel()
		super.onPause()
	}

	override fun onDestroy() {
		uiScope.cancel()
		super.onDestroy()
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == REQ_PICK_FILES && resultCode == RESULT_OK && data != null) {
			val uris = mutableListOf<Uri>()
			data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
			if (uris.isEmpty()) data.data?.let { uris.add(it) }
			importUris(uris)
			return
		}
		if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK) {
			val uri = data?.data ?: return
			val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
			currentTreeUri = uri.toString()
			prefs.edit().putString(AppPrefs.KEY_FOLDER_URI, currentTreeUri).apply()
			startOrRestartService()
			return
		}
	}

	private fun observeServerState() {
		collectJob?.cancel()
		collectJob = uiScope.launch {
			ServerService.state.collect { state ->
				tvStatus.text = if (state.running) "Status: running" else "Status: stopped"
				tvStatus.setTextColor(
					resources.getColor(
						if (state.running) R.color.status_running else R.color.status_stopped,
						theme
					)
				)
				currentUrl = state.url
				val display = if (state.mdnsUrl.isNotBlank()) "${state.url}\n${state.mdnsUrl}" else state.url
				tvUrl.text = if (state.url.isBlank()) getString(R.string.server_not_running) else display
				tvQrMode.text = if (state.url.isBlank()) getString(R.string.qr_mode_none) else getString(R.string.qr_mode_server)
				renderQr(if (state.url.isBlank()) "" else state.url)
			}
		}
	}

	private fun ensureFolderSelected(): Boolean {
		if (!currentTreeUri.isNullOrBlank()) return true
		toast("Pick a folder to share first")
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
		}
		startActivityForResult(intent, REQ_PICK_FOLDER)
		return false
	}

	private fun startOrRestartService(forcedPort: Int? = null) {
		val port = forcedPort ?: prefs.getInt(AppPrefs.KEY_PORT, 1390)
		val intent = Intent(this, ServerService::class.java).apply {
			action = ServerService.ACTION_RESTART
			putExtra(ServerService.EXTRA_PORT, port)
			putExtra(ServerService.EXTRA_TREE_URI, currentTreeUri)
			putExtra(ServerService.EXTRA_AUTH_USER, prefs.getString(AppPrefs.KEY_AUTH_USER, "") ?: "")
			putExtra(ServerService.EXTRA_AUTH_PASS, prefs.getString(AppPrefs.KEY_AUTH_PASS, "") ?: "")
		}
		runCatching { startForegroundService(intent) }
			.onFailure {
				Log.e("LanShare", "Failed to start foreground service", it)
				toast("Failed to start server service: ${it.message}")
			}
	}

	private fun renderQr(content: String) {
		if (content.isBlank()) {
			qrImage.setImageBitmap(null)
			return
		}
		val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
		val ink = AppTheme.parse(prefs.getString(AppPrefs.KEY_THEME_TEXT, null))
			?: resources.getColor(R.color.text_primary, theme)
		val paper = AppTheme.parse(prefs.getString(AppPrefs.KEY_THEME_BG, null))
			?: resources.getColor(R.color.bg_main, theme)
		runCatching { QrCodeGenerator.render(content, px, ink, paper) }
			.onSuccess { qrImage.setImageBitmap(it) }
			.onFailure { qrImage.setImageBitmap(null) }
	}

	private fun toast(msg: String) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
	}

	private fun handleShareIntent(incomingIntent: Intent?) {
		if (incomingIntent == null) return
		val action = incomingIntent.action ?: return
		val uris = mutableListOf<Uri>()
		if (action == Intent.ACTION_SEND) {
			incomingIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
		} else if (action == Intent.ACTION_SEND_MULTIPLE) {
			incomingIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
		} else return
		if (uris.isEmpty()) return
		if (currentTreeUri.isNullOrBlank()) {
			toast("Pick a folder to share first")
			return
		}
		importUris(uris)
		setIntent(Intent())
	}

	// Copies files into the shared folder using streaming (no full-file RAM load) off the UI thread.
	private fun importUris(uris: List<Uri>) {
		if (uris.isEmpty()) return
		val treeUri = currentTreeUri
		if (treeUri.isNullOrBlank()) {
			toast("Pick a folder to share first")
			return
		}
		toast("Importing ${uris.size} file(s)...")
		uiScope.launch {
			val ok = withContext(Dispatchers.IO) {
				val storage = StorageAccess(this@MainActivity, treeUri)
				if (!storage.ensureRootExists()) return@withContext -1
				var done = 0
				for (uri in uris) {
					val name = queryDisplayName(uri) ?: "shared_file"
					val saved = runCatching {
						contentResolver.openInputStream(uri)?.use { storage.saveStream("", name, it) }
					}.getOrNull()
					if (saved != null) done++
				}
				done
			}
			if (ok < 0) toast("Shared folder unavailable") else toast("Imported $ok of ${uris.size} file(s)")
		}
	}

	private fun queryDisplayName(uri: Uri): String? {
		val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
		c.use {
			if (it.moveToFirst()) return it.getString(0)
		}
		return null
	}

	companion object {
		private const val REQ_PICK_FOLDER = 1201
		private const val REQ_PICK_FILES = 1202

		fun formatSafDisplayPath(uriString: String?): String {
			if (uriString.isNullOrBlank()) return "Pick a folder"
			return try {
				val uri = Uri.parse(uriString)
				val treeId = DocumentsContract.getTreeDocumentId(uri)
				val decoded = Uri.decode(treeId)
				val parts = decoded.split(":", limit = 2)
				val volume = parts.getOrNull(0).orEmpty()
				val path = parts.getOrNull(1).orEmpty().trim('/')
				val root = if (volume == "primary") "Internal Storage" else "Storage $volume"
				if (path.isBlank()) root else "$root/$path"
			} catch (_: Throwable) {
				uriString
			}
		}
	}
}
