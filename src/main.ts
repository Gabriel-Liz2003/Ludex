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
};

const app = document.querySelector<HTMLDivElement>('#app')!;

app.innerHTML = `
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">LUDEX <span>alpha</span></div>
      <input id="search" class="search" placeholder="Buscar na biblioteca" />
      <nav>
        <button class="nav active">Biblioteca</button>
        <button class="nav">Coleções</button>
        <button class="nav">Emulação</button>
        <button class="nav">Estatísticas</button>
        <button class="nav">Configurações</button>
      </nav>
      <div id="game-list" class="game-list"></div>
    </aside>
    <main class="content">
      <section class="hero">
        <p class="eyebrow">Biblioteca universal</p>
        <h1 id="selected-title">Seus jogos, em um só lugar.</h1>
        <p id="selected-meta" class="muted">Windows, Android, consoles e emulação — local-first.</p>
        <button id="add-game" class="primary">+ Adicionar jogo</button>
      </section>
      <section class="stats">
        <article><strong id="game-count">0</strong><span>jogos</span></article>
        <article><strong id="hours-count">0h</strong><span>tempo registrado</span></article>
        <article><strong>Offline</strong><span>funciona sem conta</span></article>
      </section>
      <section>
        <div class="section-title"><h2>Biblioteca</h2><span id="filter-label">Todos</span></div>
        <div id="grid" class="grid"></div>
      </section>
    </main>
  </div>
`;

let games: Game[] = [];

const formatHours = (seconds: number) => `${Math.floor(seconds / 3600)}h`;

function render(filter = '') {
  const normalized = filter.trim().toLowerCase();
  const visible = games.filter((game) => game.title.toLowerCase().includes(normalized));
  const grid = document.querySelector<HTMLDivElement>('#grid')!;
  const list = document.querySelector<HTMLDivElement>('#game-list')!;

  grid.innerHTML = visible.length
    ? visible.map((game) => `
      <button class="game-card" data-id="${game.id}">
        <div class="cover"><span>${game.title.slice(0, 1).toUpperCase()}</span></div>
        <div class="game-info"><strong>${game.title}</strong><small>${game.platform} · ${formatHours(game.total_seconds)}</small></div>
      </button>`).join('')
    : `<div class="empty">Nenhum jogo encontrado. Adicione um jogo manualmente para começar.</div>`;

  list.innerHTML = visible.map((game) => `<button class="list-item" data-id="${game.id}">${game.title}</button>`).join('');
  document.querySelector('#game-count')!.textContent = String(games.length);
  document.querySelector('#hours-count')!.textContent = formatHours(games.reduce((sum, game) => sum + game.total_seconds, 0));
  document.querySelector('#filter-label')!.textContent = normalized ? `${visible.length} resultado(s)` : 'Todos';

  document.querySelectorAll<HTMLElement>('[data-id]').forEach((element) => {
    element.addEventListener('click', () => selectGame(element.dataset.id!));
  });
}

function selectGame(id: string) {
  const game = games.find((candidate) => candidate.id === id);
  if (!game) return;
  document.querySelector('#selected-title')!.textContent = game.title;
  document.querySelector('#selected-meta')!.textContent = `${game.platform} · ${game.source} · ${formatHours(game.total_seconds)} jogadas`;
}

async function loadGames() {
  try {
    games = await invoke<Game[]>('list_games');
  } catch (error) {
    console.error('Falha ao carregar biblioteca', error);
    games = [];
  }
  render();
}

document.querySelector<HTMLInputElement>('#search')!.addEventListener('input', (event) => {
  render((event.target as HTMLInputElement).value);
});

document.querySelector<HTMLButtonElement>('#add-game')!.addEventListener('click', async () => {
  const title = window.prompt('Nome do jogo');
  if (!title?.trim()) return;
  const platform = window.prompt('Plataforma', 'PC')?.trim() || 'PC';
  try {
    await invoke('add_manual_game', { title: title.trim(), platform });
    await loadGames();
  } catch (error) {
    window.alert(`Não foi possível adicionar o jogo: ${String(error)}`);
  }
});

void loadGames();
