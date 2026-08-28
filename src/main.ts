import { invoke } from '@tauri-apps/api/core';
import './styles.css';

type Game = {
  id: string;
  title: string;
  platform: string;
  source: string;
  executable: string | null;
  favorite: boolean;
  status: string;
  total_seconds: number;
  installed: boolean;
  providers: string[];
  active: boolean;
  last_played_at: string | null;
  session_count: number;
};

type PlaySession = {
  id: string;
  started_at: string;
  ended_at: string | null;
  duration_seconds: number;
  provider: string | null;
  recovered: boolean;
};

type GameDetails = {
  game: Game;
  stats: {
    total_seconds: number;
    last_14_seconds: number;
    last_30_seconds: number;
    session_count: number;
    average_session_seconds: number;
    last_played_at: string | null;
  };
  recent_sessions: PlaySession[];
};

type SteamStatus = {
  detected: boolean;
  root_path: string | null;
  library_count: number;
  games_found: number;
  last_sync: string | null;
};

type SteamImportResult = {
  games_found: number;
  games_created: number;
  installations_upserted: number;
  deduplicated: number;
};

type LaunchResult = {
  launched: boolean;
  already_running: boolean;
  message: string;
};

const app = document.querySelector<HTMLDivElement>('#app')!;

app.innerHTML = `
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">LUDEX <span>alpha</span></div>
      <input id="search" class="search" placeholder="Buscar na biblioteca" />
      <nav>
        <button class="nav active" data-view="library">Biblioteca</button>
        <button class="nav" disabled>Coleções</button>
        <button class="nav" disabled>Emulação</button>
        <button class="nav" disabled>Estatísticas</button>
        <button class="nav" data-view="settings">Configurações</button>
      </nav>
      <div id="game-list" class="game-list"></div>
    </aside>

    <main class="content">
      <section id="library-view">
        <section class="hero">
          <div class="hero-topline">
            <p class="eyebrow">Biblioteca universal</p>
            <span id="active-badge" class="active-badge hidden">EM JOGO</span>
          </div>
          <h1 id="selected-title">Seus jogos, em um só lugar.</h1>
          <p id="selected-meta" class="muted">Windows, Android, consoles e emulação — local-first.</p>
          <div id="selected-stats" class="selected-stats hidden"></div>
          <div class="hero-actions">
            <button id="play-game" class="play hidden">JOGAR</button>
            <button id="add-game" class="secondary">+ Adicionar jogo</button>
          </div>
          <div id="launch-message" class="launch-message hidden"></div>
        </section>

        <section class="stats">
          <article><strong id="game-count">0</strong><span>jogos</span></article>
          <article><strong id="hours-count">0h</strong><span>tempo medido pelo Ludex</span></article>
          <article><strong>Offline</strong><span>funciona sem conta</span></article>
        </section>

        <section id="recent-panel" class="recent-panel hidden">
          <div class="section-title"><h2>Sessões recentes</h2><span>medição local</span></div>
          <div id="recent-sessions" class="sessions"></div>
        </section>

        <section>
          <div class="section-title"><h2>Biblioteca</h2><span id="filter-label">Todos</span></div>
          <div id="grid" class="grid"></div>
        </section>
      </section>

      <section id="settings-view" class="hidden">
        <div class="settings-header">
          <p class="eyebrow">Configurações</p>
          <h1>Bibliotecas</h1>
          <p class="muted">Providers são sincronizados de forma independente e os dados continuam locais.</p>
        </div>
        <article class="provider-card">
          <div>
            <div class="provider-title"><strong>Steam</strong><span id="steam-detected" class="status-pill">Verificando…</span></div>
            <p id="steam-path" class="muted">Caminho ainda não verificado.</p>
          </div>
          <div class="provider-stats">
            <span><strong id="steam-libraries">—</strong>bibliotecas</span>
            <span><strong id="steam-games">—</strong>jogos encontrados</span>
            <span><strong id="steam-sync">—</strong>última sincronização</span>
          </div>
          <button id="sync-steam" class="primary">Atualizar biblioteca Steam</button>
          <p id="steam-result" class="muted small"></p>
        </article>
      </section>
    </main>
  </div>
`;

let games: Game[] = [];
let selectedId: string | null = null;
let currentFilter = '';
let currentView: 'library' | 'settings' = 'library';

const escapeHtml = (value: string) => value.replace(/[&<>'"]/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
}[char]!));

const formatDuration = (seconds: number) => {
  if (seconds < 60) return `${seconds}s`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return hours > 0 ? `${hours}h ${minutes}min` : `${minutes}min`;
};

const formatDate = (value: string | null) => {
  if (!value) return 'Nunca';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('pt-BR');
};

function render(filter = currentFilter) {
  currentFilter = filter;
  const normalized = filter.trim().toLowerCase();
  const visible = games.filter((game) => game.title.toLowerCase().includes(normalized));
  const grid = document.querySelector<HTMLDivElement>('#grid')!;
  const list = document.querySelector<HTMLDivElement>('#game-list')!;

  grid.innerHTML = visible.length
    ? visible.map((game) => `
      <button class="game-card ${game.active ? 'playing' : ''}" data-id="${game.id}">
        <div class="cover"><span>${escapeHtml(game.title.slice(0, 1).toUpperCase())}</span>${game.active ? '<b>EM JOGO</b>' : ''}</div>
        <div class="game-info">
          <strong>${escapeHtml(game.title)}</strong>
          <small>${escapeHtml(game.providers.join(', ') || game.source)} · ${game.installed ? 'Instalado' : 'Não instalado'}</small>
          <small>${formatDuration(game.total_seconds)} · ${game.session_count} sessão(ões)</small>
        </div>
      </button>`).join('')
    : `<div class="empty">Nenhum jogo encontrado. Sincronize a Steam ou adicione um jogo manualmente.</div>`;

  list.innerHTML = visible.map((game) => `<button class="list-item ${game.active ? 'playing-text' : ''}" data-id="${game.id}">${escapeHtml(game.title)}</button>`).join('');
  document.querySelector('#game-count')!.textContent = String(games.length);
  document.querySelector('#hours-count')!.textContent = formatDuration(games.reduce((sum, game) => sum + game.total_seconds, 0));
  document.querySelector('#filter-label')!.textContent = normalized ? `${visible.length} resultado(s)` : 'Todos';

  document.querySelectorAll<HTMLElement>('[data-id]').forEach((element) => {
    element.addEventListener('click', () => void selectGame(element.dataset.id!));
  });
}

async function selectGame(id: string) {
  selectedId = id;
  const details = await invoke<GameDetails | null>('get_game_details', { gameId: id });
  if (!details) return;
  const game = details.game;
  document.querySelector('#selected-title')!.textContent = game.title;
  document.querySelector('#selected-meta')!.textContent = `${game.platform} · ${game.providers.join(', ') || game.source} · ${game.installed ? 'Instalado' : 'Não instalado'} · Última vez: ${formatDate(details.stats.last_played_at)}`;

  const stats = document.querySelector<HTMLDivElement>('#selected-stats')!;
  stats.classList.remove('hidden');
  stats.innerHTML = `
    <span><strong>${formatDuration(details.stats.total_seconds)}</strong> total</span>
    <span><strong>${formatDuration(details.stats.last_14_seconds)}</strong> últimas 2 semanas</span>
    <span><strong>${formatDuration(details.stats.last_30_seconds)}</strong> últimos 30 dias</span>
    <span><strong>${formatDuration(details.stats.average_session_seconds)}</strong> média/sessão</span>`;

  const badge = document.querySelector('#active-badge')!;
  badge.classList.toggle('hidden', !game.active);
  const play = document.querySelector<HTMLButtonElement>('#play-game')!;
  play.classList.remove('hidden');
  play.disabled = game.active || !game.installed;
  play.textContent = game.active ? 'EM JOGO' : game.installed ? 'JOGAR' : 'NÃO INSTALADO';

  const panel = document.querySelector('#recent-panel')!;
  const sessions = document.querySelector('#recent-sessions')!;
  panel.classList.toggle('hidden', details.recent_sessions.length === 0);
  sessions.innerHTML = details.recent_sessions.map((session) => `
    <div class="session-row">
      <span><strong>${formatDate(session.started_at)}</strong><small>${escapeHtml(session.provider || 'local')}${session.recovered ? ' · recuperada' : ''}</small></span>
      <b>${session.ended_at ? formatDuration(session.duration_seconds) : 'EM JOGO'}</b>
    </div>`).join('');
}

async function loadGames(refreshSelection = false) {
  try {
    games = await invoke<Game[]>('list_games');
    render();
    if (selectedId && refreshSelection) await selectGame(selectedId);
  } catch (error) {
    console.error('Falha ao carregar biblioteca', error);
  }
}

async function loadSteamStatus() {
  const statusLabel = document.querySelector('#steam-detected')!;
  try {
    const status = await invoke<SteamStatus>('steam_status');
    statusLabel.textContent = status.detected ? 'Detectada' : 'Não detectada';
    statusLabel.classList.toggle('ok', status.detected);
    document.querySelector('#steam-path')!.textContent = status.root_path || 'A instalação da Steam não foi localizada.';
    document.querySelector('#steam-libraries')!.textContent = String(status.library_count);
    document.querySelector('#steam-games')!.textContent = String(status.games_found);
    document.querySelector('#steam-sync')!.textContent = status.last_sync ? formatDate(status.last_sync) : 'Nunca';
    document.querySelector<HTMLButtonElement>('#sync-steam')!.disabled = !status.detected;
  } catch (error) {
    statusLabel.textContent = 'Erro';
    document.querySelector('#steam-path')!.textContent = String(error);
  }
}

function setView(view: 'library' | 'settings') {
  currentView = view;
  document.querySelector('#library-view')!.classList.toggle('hidden', view !== 'library');
  document.querySelector('#settings-view')!.classList.toggle('hidden', view !== 'settings');
  document.querySelectorAll<HTMLButtonElement>('[data-view]').forEach((button) => button.classList.toggle('active', button.dataset.view === view));
  if (view === 'settings') void loadSteamStatus();
}

document.querySelector<HTMLInputElement>('#search')!.addEventListener('input', (event) => render((event.target as HTMLInputElement).value));

document.querySelectorAll<HTMLButtonElement>('[data-view]').forEach((button) => {
  button.addEventListener('click', () => setView(button.dataset.view as 'library' | 'settings'));
});

document.querySelector<HTMLButtonElement>('#add-game')!.addEventListener('click', async () => {
  const title = window.prompt('Nome do jogo');
  if (!title?.trim()) return;
  const platform = window.prompt('Plataforma', 'PC')?.trim() || 'PC';
  const executable = window.prompt('Executável completo (opcional)', '')?.trim() || null;
  const workingDir = executable ? (window.prompt('Diretório de trabalho (opcional)', '')?.trim() || null) : null;
  const launchArgs = executable ? (window.prompt('Argumentos de inicialização (opcional)', '')?.trim() || null) : null;
  try {
    await invoke('add_manual_game', { title: title.trim(), platform, executable, workingDir, launchArgs });
    await loadGames();
  } catch (error) {
    window.alert(`Não foi possível adicionar o jogo: ${String(error)}`);
  }
});

document.querySelector<HTMLButtonElement>('#play-game')!.addEventListener('click', async () => {
  if (!selectedId) return;
  const button = document.querySelector<HTMLButtonElement>('#play-game')!;
  const message = document.querySelector('#launch-message')!;
  button.disabled = true;
  try {
    const result = await invoke<LaunchResult>('launch_game', { gameId: selectedId });
    message.textContent = result.message;
    message.classList.remove('hidden');
  } catch (error) {
    message.textContent = String(error);
    message.classList.remove('hidden');
    button.disabled = false;
  }
});

document.querySelector<HTMLButtonElement>('#sync-steam')!.addEventListener('click', async () => {
  const button = document.querySelector<HTMLButtonElement>('#sync-steam')!;
  const resultText = document.querySelector('#steam-result')!;
  button.disabled = true;
  button.textContent = 'Sincronizando…';
  try {
    const result = await invoke<SteamImportResult>('sync_steam');
    resultText.textContent = `${result.games_found} encontrados · ${result.games_created} novos · ${result.deduplicated} associados a jogos existentes.`;
    await Promise.all([loadGames(true), loadSteamStatus()]);
  } catch (error) {
    resultText.textContent = `Falha: ${String(error)}`;
  } finally {
    button.textContent = 'Atualizar biblioteca Steam';
    button.disabled = false;
  }
});

void loadGames();
window.setInterval(() => {
  if (currentView === 'library') void loadGames(true);
}, 5000);
