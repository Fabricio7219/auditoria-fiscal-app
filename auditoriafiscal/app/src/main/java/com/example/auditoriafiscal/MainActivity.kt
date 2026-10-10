package com.example.auditoriafiscal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1. DOMÍNIO E ROTAS TIPADAS

@Serializable object RotaHome
@Serializable object RotaCategorias
@Serializable data class RotaChecklist(val categoria: String)
@Serializable data class RotaResumo(val categoria: String)

//Estados possíveis de um apontamento (não conformidade).
object StatusApontamento {
    const val PENDENTE = "Pendente"
    const val EM_ANDAMENTO = "Em Andamento"
    const val RESOLVIDO = "Resolvido"
    val todos = listOf(PENDENTE, EM_ANDAMENTO, RESOLVIDO)
}


// Registro formal de uma irregularidade encontrada em um item do checklist.
@Serializable
data class NaoConformidade(
    val idItem: Int,
    var nivelSeveridade: String = "",
    var observacao: String = "",
    var prazoResolucao: String = "",
    var status: String = StatusApontamento.PENDENTE,
    @Transient val evidencias: MutableList<ByteArray> = mutableListOf()
) {
    fun adicionarEvidencia(foto: ByteArray) {
        evidencias.add(foto)
    }

    fun obterStatus(): String = status
}

@Serializable
data class ItemAuditoria(
    val id: Int,
    val categoria: String,
    val descricao: String,
    var status: String = "Pendente",
    var naoConformidade: NaoConformidade? = null,
    var caminhoFoto: String? = null
) {
    val temEvidencia: Boolean get() = caminhoFoto != null
}


// 2. UTILITÁRIOS (data do prazo e fotos)


private fun novoFormatoData() = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).apply { isLenient = false }

//Mantém só dígitos e insere as barras: 31122026 -> 31/12/2026.
fun formatarData(texto: String): String {
    val digitos = texto.filter { it.isDigit() }.take(8)
    val sb = StringBuilder()
    digitos.forEachIndexed { i, c ->
        if (i == 2 || i == 4) sb.append('/')
        sb.append(c)
    }
    return sb.toString()
}

//Exibe os dígitos como dd/mm/aaaa sem alterar o texto digitado.
class DataVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digitos = text.text.take(8)
        val formatado = formatarData(digitos)
        val mapeamento = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val novo = when {
                    offset <= 1 -> offset
                    offset <= 3 -> offset + 1
                    else -> offset + 2
                }
                return novo.coerceAtMost(formatado.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val original = when {
                    offset <= 2 -> offset
                    offset <= 5 -> offset - 1
                    else -> offset - 2
                }
                return original.coerceAtMost(digitos.length)
            }
        }
        return TransformedText(AnnotatedString(formatado), mapeamento)
    }
}

//Data existente no calendário e não anterior a hoje. (dia que foi atualizado o projeto 10/10/2026)
fun prazoEhValido(prazo: String): Boolean {
    if (prazo.length != 10) return false
    return try {
        val formato = novoFormatoData()
        val data = formato.parse(prazo) ?: return false
        val hoje = formato.parse(formato.format(Date())) ?: return false
        !data.before(hoje)
    } catch (e: Exception) {
        false
    }
}

//Carrega a foto reduzida e corrige a rotação conforme o EXIF.
fun carregarMiniatura(caminho: String, amostra: Int): Bitmap? {
    val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
    val bitmap = BitmapFactory.decodeFile(caminho, opcoes) ?: return null
    val graus = try {
        when (ExifInterface(caminho).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        0f
    }
    if (graus == 0f) return bitmap
    val matriz = Matrix().apply { postRotate(graus) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matriz, true)
}

fun miniaturaEmBytes(caminho: String): ByteArray {
    val bitmap = carregarMiniatura(caminho, 4) ?: return ByteArray(0)
    val saida = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, saida)
    return saida.toByteArray()
}


// 3. PERSISTÊNCIA (Repository Pattern)

class AuditoriaRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("auditoria_db", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializador = ListSerializer(ItemAuditoria.serializer())

    fun salvarItens(itens: List<ItemAuditoria>) {
        prefs.edit().putString(CHAVE, json.encodeToString(serializador, itens)).apply()
    }

    fun carregarItens(): List<ItemAuditoria> {
        val salvo = prefs.getString(CHAVE, null)
        if (salvo != null) {
            try {
                return json.decodeFromString(serializador, salvo)
            } catch (e: Exception) {
                // dados antigos/corrompidos: volta para os dados simulados
            }
        }
        return itensIniciais()
    }

//Cria o arquivo onde a câmera vai gravar a foto em resolução total.
    fun novoArquivoFoto(idItem: Int): File {
        val pasta = File(context.filesDir, "evidencias").apply { mkdirs() }
        return File(pasta, "item_${idItem}_${System.currentTimeMillis()}.jpg").apply { createNewFile() }
    }

    fun excluirFoto(caminho: String?) {
        if (caminho != null) File(caminho).delete()
    }

    // Dados simulados (todos começam pendentes; categorias/itens reais dependem do stakeholder)
    private fun itensIniciais() = listOf(
        ItemAuditoria(1, "Tributos Federais", "Verificar guias de DARF pagas"),
        ItemAuditoria(2, "Tributos Federais", "Conferir apuração de IRPJ"),
        ItemAuditoria(3, "Tributos Federais", "Validar recolhimento de PIS/COFINS"),
        ItemAuditoria(4, "Tributos Estaduais", "Verificar notas fiscais de ICMS"),
        ItemAuditoria(5, "Tributos Estaduais", "Conferir guias de GNRE"),
        ItemAuditoria(6, "Trabalhista", "Analisar folhas de pagamento"),
        ItemAuditoria(7, "Trabalhista", "Verificar depósitos do FGTS")
    )

    private companion object {
        const val CHAVE = "itens_v2"
    }
}


// 4. VIEWMODEL (Gerenciamento de Estado)

class AuditoriaViewModel(private val repository: AuditoriaRepository) : ViewModel() {
    var listaCompletaItens by mutableStateOf(repository.carregarItens())
        private set

    fun atualizarItem(itemAtualizado: ItemAuditoria) {
        listaCompletaItens = listaCompletaItens.map {
            if (it.id == itemAtualizado.id) itemAtualizado else it
        }
        repository.salvarItens(listaCompletaItens)
    }

    fun atualizarStatusApontamento(idItem: Int, novoStatus: String) {
        val item = listaCompletaItens.firstOrNull { it.id == idItem } ?: return
        val nc = item.naoConformidade ?: return
        atualizarItem(item.copy(naoConformidade = nc.copy(status = novoStatus)))
    }

    fun novoArquivoFoto(idItem: Int): File = repository.novoArquivoFoto(idItem)

    fun descartarArquivo(caminho: String) = repository.excluirFoto(caminho)

    fun registrarFoto(idItem: Int, caminho: String) {
        val item = listaCompletaItens.firstOrNull { it.id == idItem } ?: return
        if (item.caminhoFoto != null && item.caminhoFoto != caminho) {
            repository.excluirFoto(item.caminhoFoto)
        }
        item.naoConformidade?.adicionarEvidencia(miniaturaEmBytes(caminho))
        atualizarItem(item.copy(caminhoFoto = caminho))
    }

    fun removerFoto(idItem: Int) {
        val item = listaCompletaItens.firstOrNull { it.id == idItem } ?: return
        repository.excluirFoto(item.caminhoFoto)
        item.naoConformidade?.evidencias?.clear()
        atualizarItem(item.copy(caminhoFoto = null))
    }

    fun limparAuditoria() {
        listaCompletaItens.forEach { repository.excluirFoto(it.caminhoFoto) }
        listaCompletaItens = listaCompletaItens.map {
            it.copy(status = "Pendente", naoConformidade = null, caminhoFoto = null)
        }
        repository.salvarItens(listaCompletaItens)
    }
}

class AuditoriaViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuditoriaViewModel(AuditoriaRepository(context.applicationContext)) as T
    }
}


// 5. ACTIVITY E NAVEGAÇÃO MAIN

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Respeita barra de status, recorte da câmera e barra de navegação
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppNavigation()
                    }
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: AuditoriaViewModel = viewModel(factory = AuditoriaViewModelFactory(context))

    NavHost(navController = navController, startDestination = RotaHome) {

        composable<RotaHome> { TelaHome(navController, viewModel) }
        composable<RotaCategorias> { TelaCategorias(navController) }

        composable<RotaChecklist> {
            val rota = it.toRoute<RotaChecklist>()
            TelaChecklistAuditoria(navController, rota.categoria, viewModel)
        }

        composable<RotaResumo> {
            val rota = it.toRoute<RotaResumo>()
            TelaResumoAuditoria(navController, rota.categoria, viewModel)
        }
    }
}


// 6. TELAS

// PIN simulado para o MVP (sem backend de autenticação)
private const val PIN_AUDITOR = "1234"

@Composable
fun TelaHome(navController: NavController, viewModel: AuditoriaViewModel) {
    val itens = viewModel.listaCompletaItens
    val pendentes = itens.count { it.status == "Pendente" }
    val statusUltima = when {
        pendentes == itens.size -> "Não Iniciada"
        pendentes == 0 -> "Concluída"
        else -> "Em Andamento"
    }
    val apontamentosAbertos = itens.count {
        it.naoConformidade != null && it.naoConformidade!!.status != StatusApontamento.RESOLVIDO
    }

    var abaSelecionada by remember { mutableIntStateOf(0) }
    val titulosAbas = listOf("Geral", "Pendentes", "Falhas", "Conformes")

    // Controle de acesso: define se a interface de edição está liberada
    var modoAuditorAutenticado by remember { mutableStateOf(false) }
    var mostrarDialogSenha by remember { mutableStateOf(false) }
    var mostrarConfirmarNova by remember { mutableStateOf(false) }

    if (mostrarDialogSenha) {
        DialogSenhaAuditor(
            onDismiss = { mostrarDialogSenha = false },
            onSuccess = {
                mostrarDialogSenha = false
                modoAuditorAutenticado = true
            }
        )
    }

    if (mostrarConfirmarNova) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmarNova = false },
            title = { Text(stringResource(R.string.confirmar_nova_titulo)) },
            text = { Text(stringResource(R.string.confirmar_nova_texto)) },
            confirmButton = {
                Button(onClick = {
                    mostrarConfirmarNova = false
                    viewModel.limparAuditoria()
                    navController.navigate(RotaCategorias)
                }) { Text(stringResource(R.string.btn_confirmar)) }
            },
            dismissButton = {
                TextButton(onClick = { mostrarConfirmarNova = false }) { Text(stringResource(R.string.btn_cancelar)) }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            if (modoAuditorAutenticado) {
                FloatingActionButton(
                    onClick = { mostrarConfirmarNova = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("+ Nova Auditoria", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            } else {
                FloatingActionButton(
                    onClick = { mostrarDialogSenha = true },
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Text("🔒 Acesso Auditor", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Text(
                "Gestão de Auditorias",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            TabRow(selectedTabIndex = abaSelecionada) {
                titulosAbas.forEachIndexed { index, titulo ->
                    Tab(
                        selected = abaSelecionada == index,
                        onClick = { abaSelecionada = index },
                        text = {
                            Text(
                                text = titulo,
                                fontWeight = if (abaSelecionada == index) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
            }

            when (abaSelecionada) {
                0 -> { // ABA GERAL
                    Column(modifier = Modifier.padding(16.dp)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sessão Atual / Última Salva:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Status: $statusUltima", color = MaterialTheme.colorScheme.secondary)
                                Text("Itens Pendentes: $pendentes", style = MaterialTheme.typography.bodySmall)
                                Text("Apontamentos em aberto: $apontamentosAbertos", style = MaterialTheme.typography.bodySmall)

                                Spacer(modifier = Modifier.height(16.dp))

                                if (modoAuditorAutenticado) {
                                    Button(
                                        onClick = { navController.navigate(RotaCategorias) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Continuar Auditoria")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { modoAuditorAutenticado = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Sair do Modo Auditor")
                                    }
                                } else {
                                    Text(
                                        "Desbloqueie o 'Acesso Auditor' para continuar ou iniciar uma nova inspeção.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> ListaSomenteLeitura(
                    itens = itens.filter { it.status == "Pendente" },
                    vazio = "Nenhum item pendente no momento."
                )
                2 -> ListaSomenteLeitura(
                    itens = itens.filter { it.status == "Não Conforme" },
                    vazio = "Nenhuma falha (Não Conformidade) registrada."
                )
                3 -> ListaSomenteLeitura(
                    itens = itens.filter { it.status == "Conforme" },
                    vazio = "Nenhum item em conformidade registrado."
                )
            }
        }
    }
}

@Composable
fun ListaSomenteLeitura(itens: List<ItemAuditoria>, vazio: String) {
    LazyColumn(modifier = Modifier.padding(16.dp)) {
        if (itens.isEmpty()) {
            item { Text(vazio, modifier = Modifier.padding(top = 16.dp)) }
        }
        items(itens) { item ->
            CardItemSomenteLeitura(item)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// Componente de Senha para Edição
@Composable
fun DialogSenhaAuditor(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var senha by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acesso Restrito") },
        text = {
            Column {
                Text("Digite a senha do auditor para criar ou editar auditorias.", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = senha,
                    onValueChange = { senha = it; erro = false },
                    label = { Text("Senha (PIN)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = erro,
                    singleLine = true
                )
                if (erro) {
                    Text("Senha incorreta.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (senha == PIN_AUDITOR) onSuccess() else erro = true }) {
                Text("Autenticar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancelar)) }
        }
    )
}

@Composable
fun MiniaturaEvidencia(caminho: String) {
    val bitmap = remember(caminho) { carregarMiniatura(caminho, 8)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Foto de evidência",
            modifier = Modifier.size(100.dp).padding(top = 8.dp)
        )
    } else {
        Text(text = stringResource(R.string.foto_anexada), style = MaterialTheme.typography.labelSmall)
    }
}

// Componente visual para as abas (só visualização)
@Composable
fun CardItemSomenteLeitura(item: ItemAuditoria) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = item.categoria, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = item.descricao, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))

            val nc = item.naoConformidade
            if (item.status == "Não Conforme" && nc != null) {
                Text(text = stringResource(R.string.status_nao_conforme, nc.nivelSeveridade), color = MaterialTheme.colorScheme.error)
                Text(text = stringResource(R.string.status_apontamento, nc.obterStatus()), style = MaterialTheme.typography.bodySmall)
                Text(text = stringResource(R.string.prazo_nao_conformidade, nc.prazoResolucao), style = MaterialTheme.typography.bodySmall)
                Text(text = stringResource(R.string.obs_nao_conformidade, nc.observacao), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(text = "Status: ${item.status}", color = MaterialTheme.colorScheme.secondary)
            }
            item.caminhoFoto?.let { MiniaturaEvidencia(it) }
        }
    }
}

@Composable
fun TelaCategorias(navController: NavController) {
    val categorias = listOf("Tributos Federais", "Tributos Estaduais", "Trabalhista")

    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.iniciar_auditoria), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.selecione_categoria), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn {
            items(categorias) { categoria ->
                Card(
                    onClick = { navController.navigate(RotaChecklist(categoria)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Text(text = categoria, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
fun TelaChecklistAuditoria(navController: NavController, categoria: String, viewModel: AuditoriaViewModel) {
    val context = LocalContext.current
    val itensExibidos = viewModel.listaCompletaItens.filter { it.categoria == categoria }
    var itemParaNaoConformidade by remember { mutableStateOf<ItemAuditoria?>(null) }

    // Sobrevivem à rotação de tela enquanto a câmera está aberta
    var idItemFoto by rememberSaveable { mutableStateOf<Int?>(null) }
    var caminhoFotoPendente by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { sucesso ->
        val id = idItemFoto
        val caminho = caminhoFotoPendente
        if (id != null && caminho != null) {
            if (sucesso && File(caminho).length() > 0) {
                viewModel.registrarFoto(id, caminho)
            } else {
                viewModel.descartarArquivo(caminho)
            }
        }
        idItemFoto = null
        caminhoFotoPendente = null
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.btn_voltar)) }
            Button(onClick = { navController.navigate(RotaResumo(categoria)) }) { Text(stringResource(R.string.btn_finalizar)) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.titulo_checklist, categoria), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(itensExibidos) { item ->
                CardItemAuditoria(
                    item = item,
                    onConformeClick = { viewModel.atualizarItem(item.copy(status = "Conforme", naoConformidade = null)) },
                    onEvidenciaClick = {
                        val arquivo = viewModel.novoArquivoFoto(item.id)
                        idItemFoto = item.id
                        caminhoFotoPendente = arquivo.absolutePath
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)
                        cameraLauncher.launch(uri)
                    },
                    onNaoConformeClick = { itemParaNaoConformidade = item },
                    onRemoverFotoClick = { viewModel.removerFoto(item.id) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    itemParaNaoConformidade?.let { item ->
        DialogNaoConformidade(
            item = item,
            onDismiss = { itemParaNaoConformidade = null },
            onSave = { severidade, observacao, prazo ->
                val nova = NaoConformidade(
                    idItem = item.id,
                    nivelSeveridade = severidade,
                    observacao = observacao,
                    prazoResolucao = prazo
                )
                viewModel.atualizarItem(item.copy(status = "Não Conforme", naoConformidade = nova))
                itemParaNaoConformidade = null
            }
        )
    }
}

@Composable
fun CardItemAuditoria(item: ItemAuditoria, onConformeClick: () -> Unit, onEvidenciaClick: () -> Unit, onNaoConformeClick: () -> Unit, onRemoverFotoClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = item.descricao, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)

            val nc = item.naoConformidade
            if (item.status == "Não Conforme" && nc != null) {
                Text(text = stringResource(R.string.status_nao_conforme, nc.nivelSeveridade), color = MaterialTheme.colorScheme.error)
                Text(text = stringResource(R.string.prazo_nao_conformidade, nc.prazoResolucao), style = MaterialTheme.typography.bodySmall)
            } else if (item.status == "Conforme") {
                Text(text = stringResource(R.string.status_conforme), color = MaterialTheme.colorScheme.primary)
            } else {
                Text(text = stringResource(R.string.status_pendente), color = MaterialTheme.colorScheme.secondary)
            }

            item.caminhoFoto?.let {
                MiniaturaEvidencia(it)
                TextButton(onClick = onRemoverFotoClick) { Text(stringResource(R.string.btn_remover_foto)) }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onConformeClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text(stringResource(R.string.btn_ok)) }
                Button(onClick = onEvidenciaClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text(stringResource(R.string.btn_foto)) }
                Button(onClick = onNaoConformeClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.btn_falha)) }
            }
        }
    }
}

@Composable
fun DialogNaoConformidade(item: ItemAuditoria, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var severidade by remember { mutableStateOf("") }
    var observacao by remember { mutableStateOf("") }
    var prazoDigitos by remember { mutableStateOf("") }
    val prazo = formatarData(prazoDigitos)

    val prazoValido = prazoEhValido(prazo)
    // Só libera salvar com severidade, motivo e prazo válido
    val podeSalvar = severidade.isNotEmpty() && observacao.isNotBlank() && prazoValido

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.titulo_nao_conformidade)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.item_descricao, item.descricao), fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = observacao,
                    onValueChange = { observacao = it },
                    label = { Text(stringResource(R.string.label_observacao)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = prazoDigitos,
                    onValueChange = { prazoDigitos = it.filter { c -> c.isDigit() }.take(8) },
                    visualTransformation = DataVisualTransformation(),
                    label = { Text(stringResource(R.string.label_prazo)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = prazo.length == 10 && !prazoValido,
                    modifier = Modifier.fillMaxWidth()
                )
                if (prazo.length == 10 && !prazoValido) {
                    Text(stringResource(R.string.prazo_invalido), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.selecione_severidade))
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(
                        stringResource(R.string.severidade_baixa),
                        stringResource(R.string.severidade_media),
                        stringResource(R.string.severidade_alta)
                    ).forEach { nivel ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RadioButton(selected = severidade == nivel, onClick = { severidade = nivel })
                            Text(nivel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(severidade, observacao, prazo) }, enabled = podeSalvar) {
                Text(stringResource(R.string.btn_salvar))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancelar)) } }
    )
}

@Composable
fun TelaResumoAuditoria(navController: NavController, categoria: String, viewModel: AuditoriaViewModel) {
    val itensAuditados = viewModel.listaCompletaItens.filter { it.categoria == categoria }
    val pendentes = itensAuditados.count { it.status == "Pendente" }
    val conformes = itensAuditados.count { it.status == "Conforme" }
    val naoConformes = itensAuditados.count { it.status == "Não Conforme" }

    val apontamentos = itensAuditados.filter { it.naoConformidade != null }
    val apPendentes = apontamentos.count { it.naoConformidade!!.obterStatus() == StatusApontamento.PENDENTE }
    val apAndamento = apontamentos.count { it.naoConformidade!!.obterStatus() == StatusApontamento.EM_ANDAMENTO }
    val apResolvidos = apontamentos.count { it.naoConformidade!!.obterStatus() == StatusApontamento.RESOLVIDO }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text(stringResource(R.string.resumo_auditoria), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.resultados_consolidados), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.qtd_conformes, conformes), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.qtd_nao_conformes, naoConformes), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.qtd_pendentes, pendentes), color = MaterialTheme.colorScheme.secondary)

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.apontamentos_titulo), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(stringResource(R.string.qtd_ap_pendentes, apPendentes))
                Text(stringResource(R.string.qtd_ap_andamento, apAndamento))
                Text(stringResource(R.string.qtd_ap_resolvidos, apResolvidos))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.detalhamento_itens), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(itensAuditados) { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = item.descricao, fontWeight = FontWeight.Bold)
                        val nc = item.naoConformidade
                        if (item.status == "Não Conforme" && nc != null) {
                            Text(text = stringResource(R.string.status_nao_conforme, nc.nivelSeveridade), color = MaterialTheme.colorScheme.error)
                            Text(text = stringResource(R.string.obs_nao_conformidade, nc.observacao), style = MaterialTheme.typography.bodySmall)
                            Text(text = stringResource(R.string.prazo_nao_conformidade, nc.prazoResolucao), style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            // Acompanhamento do apontamento: pendente -> em andamento -> resolvido
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusApontamento.todos.forEach { status ->
                                    FilterChip(
                                        selected = nc.obterStatus() == status,
                                        onClick = { viewModel.atualizarStatusApontamento(item.id, status) },
                                        label = { Text(status, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        } else if (item.status == "Conforme") {
                            Text(text = stringResource(R.string.status_conforme), color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(text = stringResource(R.string.status_pendente), color = MaterialTheme.colorScheme.secondary)
                        }
                        item.caminhoFoto?.let { MiniaturaEvidencia(it) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { navController.popBackStack<RotaHome>(inclusive = false) },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(stringResource(R.string.btn_concluir))
        }
    }
}
