# App de Auditoria Fiscal

App Android para padronizar verificações fiscais em campo. O auditor percorre um checklist no celular, fotografa evidências e acompanha as não conformidades até a resolução, inclusive offline.

Projeto da disciplina **ISW036 – Laboratório de Desenvolvimento para Dispositivos Móveis** (Fatec), sob orientação do **Prof. Dr. Desiré Nguessan**.

## 🌐 Ver online

**https://fabricio7219.github.io/auditoria-fiscal-app/**

A página tem três abas:

| Aba | O que mostra |
|---|---|
| **Projeto** | Apresentação: problema, funcionalidades, arquitetura MVVM, tecnologias e próximos passos |
| **App web** | Versão do app que roda no navegador, com as mesmas telas e regras do Android (PIN de teste: `1234`) |
| **Código explicado** | Cada arquivo do projeto com a explicação ao lado de cada trecho |

Links diretos: [`#projeto`](https://fabricio7219.github.io/auditoria-fiscal-app/#projeto) · [`#app`](https://fabricio7219.github.io/auditoria-fiscal-app/#app) · [`#codigo`](https://fabricio7219.github.io/auditoria-fiscal-app/#codigo)

## Funcionalidades do MVP

- **Painel de gestão** com abas de leitura livre (Pendentes, Falhas, Conformes)
- **Modo Auditor** protegido por PIN para criar ou editar auditorias
- **Checklist por categoria fiscal**: Tributos Federais, Tributos Estaduais e Trabalhista
- **Foto de evidência** pela câmera nativa (FileProvider), vinculada ao item
- **Não conformidade** com severidade (Baixa, Média, Alta), motivo e prazo obrigatórios
- **Resumo** com totais e fluxo do apontamento: Pendente → Em Andamento → Resolvido
- **Offline**: tudo fica salvo no aparelho

## Tecnologias

- Kotlin + Jetpack Compose (Material 3)
- Arquitetura MVVM com Repository Pattern
- Navigation Compose com rotas tipadas
- SharedPreferences + kotlinx.serialization (persistência local)
- minSdk 24 (Android 7.0+)

Dados de teste mockados e anonimizados, em respeito à LGPD.

## Estrutura do repositório

```
auditoriafiscal/   projeto Android (abrir esta pasta no Android Studio)
docs/              página web publicada no GitHub Pages
```

Todo o código do app está em `auditoriafiscal/app/src/main/java/com/example/auditoriafiscal/MainActivity.kt`, dividido em 6 seções: domínio e rotas, utilitários, persistência, ViewModel, Activity/navegação e telas.

## Como rodar o app Android

1. Clone o repositório:
   ```
   git clone https://github.com/Fabricio7219/auditoria-fiscal-app.git
   ```
2. No Android Studio, use **File → Open** e selecione a pasta **`auditoriafiscal`** (a que tem o `settings.gradle.kts`).
3. Aguarde o **Gradle Sync**. O projeto usa AGP 9.4 e compileSdk 37, então use um Android Studio atualizado.
4. Crie um emulador em **Device Manager** ou conecte um celular com depuração USB.
5. Clique em **Run ▶** (Shift + F10).

PIN do Modo Auditor: `1234`.

## Próximos passos

- [ ] Migrar a persistência para Room (SQLite)
- [ ] Notificações locais para vencimento de prazos
- [ ] Exportar o relatório final em PDF
- [ ] Sincronização em nuvem e login multiusuário

## Créditos

Código do app baseado no repositório da equipe: [Niknagy/auditoriafiscalapp](https://github.com/Niknagy/auditoriafiscalapp).
