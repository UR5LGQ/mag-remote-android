let currentQuery = '';
let currentNextPageToken = '';
let currentPlaylistId = '';
let currentPlaylistNextPageToken = '';

let lastSearchResults = [];

async function doSearch(isLoadMore = false) {
    const qInput = document.getElementById('query');
    const q = qInput ? qInput.value.trim() : '';
    if (!q) return;

    if (!isLoadMore) {
        currentQuery = q;
        currentNextPageToken = '';
        lastSearchResults = [];
        document.getElementById('results-container').innerHTML = '';
    }

    const loadingEl = document.getElementById('loading');
    if (loadingEl) loadingEl.style.display = 'block';

    try {
        // 👉 ЕСЛИ ЗАПУЩЕНО В ANDROID APP С NEWPIPEEXTRACTOR
        if (window.AndroidBridge && typeof window.AndroidBridge.searchVideos === 'function') {
            const callbackName = 'handleNativeSearchResults_' + Date.now();
            
            // Создаем временный глобальный обработчик для ответа из Kotlin
            window[callbackName] = function(items) {
                if (loadingEl) loadingEl.style.display = 'none';
                
                if (!items || items.length === 0) {
                    const container = document.getElementById('results-container');
                    if (!isLoadMore) container.innerHTML = '<div style="text-align:center;font-size:18px;">Ничего не найдено.</div>';
                    return;
                }

                lastSearchResults = isLoadMore ? lastSearchResults.concat(items) : items;
                
                // Рендерим стандартные элементы (NewPipe выдает формат, совместимый с renderItems)
                renderItems(items, isLoadMore);
                
                // Пагинацию для локального NewPipe поиска пока скрываем (можно доработать при желании)
                toggleLoadMoreButton('search', false);

                // Очищаем за собой глобальную функцию
                delete window[callbackName];
            };

            // Вызываем нативный метод Android
            window.AndroidBridge.searchVideos(q, callbackName);
            return;
        }

        // --- СТАНДАРТНАЯ СЕТЕВАЯ ЛОГИКА (ЕСЛИ ОТКРЫТО В БРАУЗЕРЕ НА ПК) ---
        let url = `/search?q=${encodeURIComponent(currentQuery)}`;
        if (currentNextPageToken) url += `&pageToken=${encodeURIComponent(currentNextPageToken)}`;

        const res = await fetch(url);
        if (res.status === 401) {
            // Сессия слетела во время работы
            window.location.href = '/phone.html';
            return;
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);

        const data = await res.json();
        if (loadingEl) loadingEl.style.display = 'none';

        const items = data.items || [];

        // 👉 ЕСЛИ ЭТО ВИДЕО СО СТОРОННЕГО САЙТА (ПРЯМАЯ ССЫЛКА)
        if (items.length > 0 && items[0].type === 'direct_video') {
            lastSearchResults = items;
            renderDirectItems(items); // Вызываем новую отдельную функцию отрисовки
            return; // Прерываем выполнение, пагинация YouTube здесь не нужна
        }

        // --- ДАЛЬШЕ ИДЕТ СТАНДАРТНАЯ ЛОГИКА YOUTUBE ---
        currentNextPageToken = data.nextPageToken || '';
        
        if (!isLoadMore) {
            lastSearchResults = items;
        } else {
            lastSearchResults = lastSearchResults.concat(items);
        }

        renderItems(items, isLoadMore);
        toggleLoadMoreButton('search', !!currentNextPageToken);

    } catch (err) {
        if (loadingEl) loadingEl.style.display = 'none';
        alert('Ошибка поиска: ' + err.message);
    }
}

async function openPlaylist(playlistId, playlistTitle, isLoadMore = false) {
    const container = document.getElementById('results-container');

    if (!isLoadMore) {
        currentPlaylistId = playlistId;
        currentPlaylistNextPageToken = '';

        container.innerHTML = `
            <div style="margin-bottom: 15px;">
                <button class="search-btn" style="background:#555; margin-bottom:10px;" onclick="backToSearchResults()">⬅ Назад к результатам поиска</button>
                <h3 style="margin:5px 0;">📁 Плейлист: ${playlistTitle}</h3>
            </div>
            <div id="playlist-items"></div>
        `;
    }

    const loadingEl = document.getElementById('loading');
    if (loadingEl) loadingEl.style.display = 'block';

    try {
        let url = `/playlist?id=${encodeURIComponent(currentPlaylistId)}`;
        if (currentPlaylistNextPageToken) url += `&pageToken=${encodeURIComponent(currentPlaylistNextPageToken)}`;

        const res = await fetch(url);
        if (res.status === 401) {
            // Сессия слетела во время работы
            window.location.href = '/phone.html';
            return;
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);

        const data = await res.json();
        if (loadingEl) loadingEl.style.display = 'none';

        currentPlaylistNextPageToken = data.nextPageToken || '';
        renderPlaylistItems(data.items || []);
        toggleLoadMoreButton('playlist', !!currentPlaylistNextPageToken);

    } catch (err) {
        if (loadingEl) loadingEl.style.display = 'none';
        alert('Ошибка загрузки плейлиста: ' + err.message);
    }
}

function backToSearchResults() {
    const container = document.getElementById('results-container');
    container.innerHTML = '';
    renderItems(lastSearchResults, false);
    toggleLoadMoreButton('search', !!currentNextPageToken);
}

function renderItems(items, append = false) {
    const container = document.getElementById('results-container');
    
    const oldBtn = document.getElementById('load-more-btn');
    if (oldBtn) oldBtn.remove();

    if (!append && items.length === 0) {
        container.innerHTML = '<div style="text-align:center;font-size:18px;">Ничего не найдено.</div>';
        return;
    }

    items.forEach(item => {
        const card = document.createElement('div');
        card.className = 'video-card';

        if (item.type === 'playlist') {
            card.style.borderLeft = '5px solid #FF9800';
            card.addEventListener('click', () => openPlaylist(item.id, item.title));
        } else {
            card.addEventListener('click', () => startVideo(item.id, item.title, item.thumbnail));
        }

        const img = document.createElement('img');
        img.src = item.thumbnail || `https://i.ytimg.com/vi/${item.id}/mqdefault.jpg`;
        img.onerror = function() { this.style.display = 'none'; };

        const title = document.createElement('div');
        title.className = 'video-title';
        title.innerHTML = (item.type === 'playlist' ? '📁 [ПЛЕЙЛИСТ] ' : '') + (item.title || 'Видео');

        card.appendChild(img);
        card.appendChild(title);
        container.appendChild(card);
    });
}

function renderPlaylistItems(items) {
    const target = document.getElementById('playlist-items');
    
    const oldBtn = document.getElementById('load-more-btn');
    if (oldBtn) oldBtn.remove();

    items.forEach(item => {
        const card = document.createElement('div');
        card.className = 'video-card';
        card.addEventListener('click', () => startVideo(item.id, item.title, item.thumbnail));

        const img = document.createElement('img');
        img.src = item.thumbnail || `https://i.ytimg.com/vi/${item.id}/mqdefault.jpg`;
        img.onerror = function() { this.style.display = 'none'; };

        const title = document.createElement('div');
        title.className = 'video-title';
        title.textContent = item.title || 'Видео';

        card.appendChild(img);
        card.appendChild(title);
        target.appendChild(card);
    });
}

function renderDirectItems(items) {
    const container = document.getElementById('results-container');
    
    // Удаляем старые кнопки "Загрузить еще", если они были
    const oldBtn = document.getElementById('load-more-btn');
    if (oldBtn) oldBtn.remove();

    if (items.length === 0) {
        container.innerHTML = '<div style="text-align:center;font-size:18px;">Ничего не найдено по ссылке.</div>';
        return;
    }

    items.forEach(item => {
        const card = document.createElement('div');
        card.className = 'video-card';
        card.style.borderLeft = '5px solid #4CAF50'; // Зеленая полоска для сторонних сайтов

        // По клику запускаем воспроизведение прямой ссылки
        card.addEventListener('click', () => startDirectVideo(item.id, item.title, item.thumbnail));

        const img = document.createElement('img');
        // Если у серии/видео нет обложки, скрываем элемент или ставим заглушку
        if (item.thumbnail) {
            img.src = item.thumbnail;
        } else {
            img.style.display = 'none';
        }
        img.onerror = function() { this.style.display = 'none'; };

        const title = document.createElement('div');
        title.className = 'video-title';
        title.innerHTML = '🌐 ' + (item.title || 'Видео по ссылке');

        card.appendChild(img);
        card.appendChild(title);
        container.appendChild(card);
    });
}

async function startDirectVideo(videoUrl, title, thumbnail) {
    document.getElementById('search-screen').style.display = 'none';
    document.getElementById('remote-screen').style.display = 'block';
    
    isYoutubePaused = false;
    updateYoutubePauseButton();

    const stateEl = document.getElementById('player-state');
    if (stateEl) stateEl.textContent = '⏳ Загрузка потока...';
    
    try {
        let url = '/control?action=load&val=' + encodeURIComponent(videoUrl);
        if (title) url += '&title=' + encodeURIComponent(title);
        if (thumbnail) url += '&thumbnail=' + encodeURIComponent(thumbnail);
        
        const res = await fetch(url);
        const result = await res.json();

        if (!result.ok) {
            document.getElementById('remote-screen').style.display = 'none';
            document.getElementById('search-screen').style.display = 'block';
            alert('Не удалось запустить видео: ' + (result.error || 'Ошибка'));
        }
    } catch (err) {
        document.getElementById('remote-screen').style.display = 'none';
        document.getElementById('search-screen').style.display = 'block';
        alert('Ошибка связи: ' + err.message);
    }
}

function toggleLoadMoreButton(type, hasMore) {
    const oldBtn = document.getElementById('load-more-btn');
    if (oldBtn) oldBtn.remove();

    if (!hasMore) return;

    const btn = document.createElement('button');
    btn.id = 'load-more-btn';
    btn.className = 'search-btn';
    btn.style.marginTop = '15px';
    btn.style.background = '#333';
    btn.textContent = '➕ Загрузить еще';

    if (type === 'search') {
        btn.onclick = () => doSearch(true);
        document.getElementById('results-container').appendChild(btn);
    } else {
        btn.onclick = () => openPlaylist(currentPlaylistId, '', true);
        document.getElementById('playlist-items').appendChild(btn);
    }
}