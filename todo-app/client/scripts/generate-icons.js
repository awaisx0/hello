// One-off build step: generates PWA icons as raw PNGs using only Node core
// modules (zlib for deflate + a hand-rolled CRC32), no image libs needed.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT_DIR = path.join(__dirname, '..', 'icons');
fs.mkdirSync(OUT_DIR, { recursive: true });

const BG = [0x4f, 0x7c, 0xff];
const FG = [0xff, 0xff, 0xff];

let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = crcTable[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function buildPng(width, height, pixelFn) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  let offset = 0;
  for (let y = 0; y < height; y++) {
    raw[offset++] = 0;
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = pixelFn(x, y);
      raw[offset++] = r;
      raw[offset++] = g;
      raw[offset++] = b;
      raw[offset++] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const idat = zlib.deflateSync(raw, { level: 9 });
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([signature, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

function distToSegment(px, py, ax, ay, bx, by) {
  const abx = bx - ax;
  const aby = by - ay;
  const abLen2 = abx * abx + aby * aby;
  let t = abLen2 === 0 ? 0 : ((px - ax) * abx + (py - ay) * aby) / abLen2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * abx), py - (ay + t * aby));
}

function drawIcon(size, padding) {
  const contentSize = size * (1 - padding * 2);
  const off = size * padding;
  const toAbs = ([x, y]) => [off + x * contentSize, off + y * contentSize];
  const [ax, ay] = toAbs([0.2, 0.52]);
  const [bx, by] = toAbs([0.42, 0.72]);
  const [cx, cy] = toAbs([0.8, 0.28]);
  const strokeWidth = size * 0.09;

  return buildPng(size, size, (x, y) => {
    const d1 = distToSegment(x + 0.5, y + 0.5, ax, ay, bx, by);
    const d2 = distToSegment(x + 0.5, y + 0.5, bx, by, cx, cy);
    return d1 <= strokeWidth / 2 || d2 <= strokeWidth / 2 ? [...FG, 255] : [...BG, 255];
  });
}

const targets = [
  { file: 'icon-192.png', size: 192, padding: 0.18 },
  { file: 'icon-512.png', size: 512, padding: 0.18 },
  { file: 'icon-maskable-192.png', size: 192, padding: 0.3 },
  { file: 'icon-maskable-512.png', size: 512, padding: 0.3 },
];

targets.forEach(({ file, size, padding }) => {
  fs.writeFileSync(path.join(OUT_DIR, file), drawIcon(size, padding));
  console.log(`Wrote ${file}`);
});
