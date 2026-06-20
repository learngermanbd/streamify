// Import M3U channels into Streamify database
// Usage: node tools/import_m3u.js <path-to-m3u-file>
const fs = require('fs');
const http = require('http');
const https = require('https');
const { Client } = require('../backend/node_modules/pg');

const M3U_FILE = process.argv[2] || 'Downloads/World Cup.m3u';
const TIMEOUT = 5000; // 5 seconds per URL test
const CONCURRENT = 10; // Test 10 URLs at a time

// ── DB config ──
const db = new Client({
  host: 'aws-1-ap-south-1.pooler.supabase.com',
  port: 6543,
  user: 'postgres.izpvcoikstplxcqzgsfh',
  password: 'lmtUaw5sn6hQeADR',
  database: 'postgres',
  ssl: { rejectUnauthorized: false },
  connectionTimeoutMillis: 15000,
});

// ── Category rules (order matters — first match wins) ──
const CATEGORIES = [
  { id: 'worldcup2026', name: '🏆 World Cup 2026', icon: '🏆', match: /world\s*cup|fifa|fwc\s*2026/i, sort: 1 },
  { id: 'beinsports', name: 'Bein Sports', icon: '📺', match: /bein(?:\s*sports?)?\s*(?!xtra|espa)/i, sort: 2 },
  { id: 'beinesp', name: 'Bein Sports Español', icon: '🇪🇸', match: /bein.*(xtra|españ|esp)/i, sort: 3 },
  { id: 'espn', name: 'ESPN', icon: '🏀', match: /espn/i, sort: 4 },
  { id: 'foxsports', name: 'Fox Sports', icon: '🦊', match: /fox\s*(sports?|deportes|prem|ny)/i, sort: 5 },
  { id: 'dazn', name: 'DAZN', icon: '🥊', match: /dazn/i, sort: 6 },
  { id: 'starsports', name: 'Star Sports', icon: '⭐', match: /star\s*sports?/i, sort: 7 },
  { id: 'sonysports', name: 'Sony Sports', icon: '🎯', match: /sony\s*(sports?|liv)/i, sort: 8 },
  { id: 'tntsports', name: 'TNT Sports', icon: '💥', match: /tnt\s*sports?/i, sort: 9 },
  { id: 'dsports', name: 'DSports', icon: '⚽', match: /dsports|dspor/i, sort: 10 },
  { id: 'winsports', name: 'Win Sports', icon: '🏅', match: /win\s*sport/i, sort: 11 },
  { id: 'tycsports', name: 'TYC Sports', icon: '🇦🇷', match: /tyc\s*sports?/i, sort: 12 },
  { id: 'skysports', name: 'Sky Sports', icon: '☁️', match: /sky\s*sport/i, sort: 13 },
  { id: 'premiersports', name: 'Premier Sports', icon: '🏴', match: /premier\s*sports?/i, sort: 14 },
  { id: 'eurosport', name: 'Eurosport', icon: '🇪🇺', match: /eurosport/i, sort: 15 },
  { id: 'matchtv', name: 'Match TV / Setanta', icon: '🇷🇺', match: /матч|match\s*tv|setenta|setanta/i, sort: 16 },
  { id: 'ziggo', name: 'Ziggo Sport', icon: '🇳🇱', match: /ziggo/i, sort: 17 },
  { id: 'canalplus', name: 'Canal+ Sport', icon: '🇫🇷', match: /canal\+/i, sort: 18 },
  { id: 'sporttv', name: 'Sport TV', icon: '🇵🇹', match: /sport\s*tv|sporttv/i, sort: 19 },
  { id: 'bangladesh', name: 'Bangladesh 🇧🇩', icon: '🇧🇩', match: /btv|atn|jamuna|somoy|channel\s*i|ntv|ekattor|independent|deepto|rajdhani|t\s*sports|bioscope|bangla|bd|🇧🇩|tspor/i, sort: 20 },
  { id: 'cricket', name: 'Cricket', icon: '🏏', match: /cricket|criclife|psl|fancode|willow/i, sort: 21 },
  { id: 'realmadrid', name: 'Real Madrid TV', icon: '⚪', match: /real\s*madrid/i, sort: 22 },
  { id: 'nbagolf', name: 'NBA / Golf / Tennis', icon: '🎾', match: /nba|golf|tennis/i, sort: 23 },
  { id: 'redbull', name: 'Red Bull TV', icon: '🐂', match: /red\s*bull/i, sort: 24 },
  { id: 'latinamerica', name: 'Latin America Sports', icon: '🌎', match: /claro|tudn|telemundo|azteca|tigo|combate|movistar|gol\s*tv|telefe|rcn|caracol|mundial|universo/i, sort: 25 },
  { id: 'rssports', name: 'RS Sports Brazil', icon: '🇧🇷', match: /rs\s*(sports?|tv|news|premiere)/i, sort: 26 },
  { id: 'fubo', name: 'Fubo / Misc Sports', icon: '📡', match: /fubo|chopper|commune|drone|field.*stream|fuel\s*tv|gopro|h2o|harlem|horse|hard\s*knocks|fight\s*network|documentary|idman/i, sort: 27 },
  { id: 'news', name: 'News & Info', icon: '📰', match: /news/i, sort: 28 },
  { id: 'religious', name: 'Religious', icon: '🕌', match: /quran|sunnah/i, sort: 29 },
  { id: 'othersports', name: 'Other Sports', icon: '🎬', match: /sport|deport|laliga|lig/i, sort: 30 },
  { id: 'other', name: 'Other', icon: '📺', match: /./, sort: 99 },
];

// ── Parse M3U ──
function parseM3U(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const lines = content.split('\n').map(l => l.trim());
  const channels = [];
  let currentName = '';

  for (const line of lines) {
    if (line.startsWith('#EXTINF:')) {
      const comma = line.lastIndexOf(',');
      currentName = comma > 0 ? line.slice(comma + 1).trim() : 'Unknown';
    } else if (line && !line.startsWith('#')) {
      if (currentName && (line.startsWith('http://') || line.startsWith('https://'))) {
        channels.push({ name: currentName, url: line });
      }
      currentName = '';
    }
  }
  return channels;
}

// ── Test a single URL ──
function testUrl(url) {
  return new Promise((resolve) => {
    const isHttps = url.startsWith('https://');
    const mod = isHttps ? https : http;
    const req = mod.get(url, { timeout: TIMEOUT, rejectUnauthorized: false }, (res) => {
      res.resume();
      resolve({ url, online: true, status: res.statusCode });
    });
    req.on('error', () => resolve({ url, online: false, status: 0 }));
    req.on('timeout', () => { req.destroy(); resolve({ url, online: false, status: 0 }); });
    setTimeout(() => { req.destroy(); resolve({ url, online: false, status: 0 }); }, TIMEOUT);
  });
}

// ── Test URLs in batches ──
async function testAllUrls(channels) {
  console.log(`Testing ${channels.length} URLs (timeout: ${TIMEOUT}ms, concurrent: ${CONCURRENT})...`);
  const results = [];
  for (let i = 0; i < channels.length; i += CONCURRENT) {
    const batch = channels.slice(i, i + CONCURRENT);
    const batchResults = await Promise.all(batch.map(ch => testUrl(ch.url)));
    for (let j = 0; j < batch.length; j++) {
      const ch = batch[j];
      const r = batchResults[j];
      const status = r.online ? '✅' : '❌';
      const detail = r.online ? ` (${r.status})` : ' OFFLINE';
      console.log(`  ${status} ${ch.name.substring(0, 40).padEnd(40)} ${detail}`);
      if (r.online) results.push(ch);
    }
    // Progress
    process.stdout.write(`\r  Progress: ${Math.min(i + CONCURRENT, channels.length)}/${channels.length}`);
  }
  console.log(`\n  Online: ${results.length} / ${channels.length} (${Math.round(results.length / channels.length * 100)}%)`);
  return results;
}

// ── Categorize ──
function categorize(channels) {
  const categorized = [];
  for (const ch of channels) {
    for (const cat of CATEGORIES) {
      if (cat.match.test(ch.name)) {
        ch.categoryId = cat.id;
        ch.categoryName = cat.name;
        categorized.push(ch);
        break;
      }
    }
  }
  return categorized;
}

// ── Insert into database ──
async function insertToDB(channels) {
  await db.connect();
  console.log('Connected to DB');

  // Clear existing data
  await db.query('DELETE FROM "Channel"');
  await db.query('DELETE FROM "Category"');
  console.log('Cleared existing channels & categories');

  // Get unique categories from the assigned ones
  const catMap = new Map();
  for (const ch of channels) {
    if (!catMap.has(ch.categoryId)) {
      const catDef = CATEGORIES.find(c => c.id === ch.categoryId);
      catMap.set(ch.categoryId, {
        id: ch.categoryId,
        name: ch.categoryName,
        iconUrl: catDef?.icon || '📺',
        sortOrder: catDef?.sort || 99,
        isVisible: true,
      });
    }
  }

  // Insert categories
  for (const [id, cat] of catMap) {
    await db.query(
      `INSERT INTO "Category" (id, name, "iconUrl", "sortOrder", "isVisible") VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (id) DO UPDATE SET name = $2, "iconUrl" = $3, "sortOrder" = $4, "isVisible" = $5`,
      [cat.id, cat.name, cat.iconUrl, cat.sortOrder, cat.isVisible]
    );
  }
  console.log(`Inserted ${catMap.size} categories`);

  // Insert channels
  let inserted = 0;
  for (let i = 0; i < channels.length; i++) {
    const ch = channels[i];
    const id = `m3u_${String(i).padStart(4, '0')}`;
    await db.query(
      `INSERT INTO "Channel" (id, name, "logoUrl", "streamUrl", "categoryId", "isActive", "sortOrder", "createdAt", "updatedAt")
       VALUES ($1, $2, $3, $4, $5, $6, $7, NOW(), NOW())
       ON CONFLICT (id) DO UPDATE SET name = $2, "streamUrl" = $4, "categoryId" = $5, "sortOrder" = $7, "updatedAt" = NOW()`,
      [id, ch.name, '', ch.url, ch.categoryId, true, i]
    );
    inserted++;
  }
  console.log(`Inserted ${inserted} channels`);

  await db.end();
}

// ── Generate summary ──
function summarize(channels) {
  const counts = {};
  for (const ch of channels) {
    counts[ch.categoryName] = (counts[ch.categoryName] || 0) + 1;
  }
  console.log('\n📊 Channel Summary:');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  for (const [cat, count] of Object.entries(counts).sort((a, b) => b[1] - a[1])) {
    const bar = '█'.repeat(Math.round(count / 2));
    console.log(`  ${cat.padEnd(30)} ${String(count).padStart(3)} ${bar}`);
  }
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log(`  TOTAL: ${channels.length} channels in ${Object.keys(counts).length} categories`);
}

// ── Main ──
(async () => {
  console.log('═══════════════════════════════════════');
  console.log('  Streamify M3U Import');
  console.log('═══════════════════════════════════════\n');

  // Parse
  console.log('📁 Parsing M3U...');
  const allChannels = parseM3U(M3U_FILE);
  console.log(`  Found ${allChannels.length} channels\n`);

  // Test & filter offline
  console.log('🔍 Testing URLs (removing offline servers)...');
  const onlineChannels = await testAllUrls(allChannels);
  console.log(`  Removed ${allChannels.length - onlineChannels.length} offline channels\n`);

  // Categorize
  console.log('🏷️  Categorizing...');
  const categorized = categorize(onlineChannels);

  // Summary
  summarize(categorized);

  // Insert
  console.log('\n💾 Inserting into database...');
  await insertToDB(categorized);

  console.log('\n✅ Done!');
  console.log('═══════════════════════════════════════');
})().catch(err => {
  console.error('FATAL:', err.message);
  process.exit(1);
});
