require('dotenv').config();
const path = require('path');
const express = require('express');
const cors = require('cors');

const todosRouter = require('./routes/todos');
const pushRouter = require('./routes/push');
const dataRouter = require('./routes/data');
const scheduler = require('./scheduler');

const app = express();
app.use(cors());
app.use(express.json({ limit: '2mb' }));

app.use('/api', todosRouter);
app.use('/api', pushRouter);
app.use('/api', dataRouter);

const clientDir = path.join(__dirname, '..', '..', 'client');
app.use(express.static(clientDir));
app.get('/', (req, res) => res.sendFile(path.join(clientDir, 'index.html')));

app.use((req, res) => res.status(404).json({ error: 'Not found' }));

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'Internal server error' });
});

const PORT = process.env.PORT || 4000;
app.listen(PORT, () => {
  console.log(`Todo server listening on http://localhost:${PORT}`);
  scheduler.start();
});
