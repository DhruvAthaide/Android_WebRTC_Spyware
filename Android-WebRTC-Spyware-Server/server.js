const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  }
});

// Serve static files from the 'public' directory
const publicPath = path.join(__dirname, 'public');
if (!fs.existsSync(publicPath)) {
  console.error(`FATAL: Public directory not found at: ${publicPath}`);
  process.exit(1);
}
app.use(express.static(publicPath));

// Log all incoming requests
app.use((req, res, next) => {
  console.log(`HTTP request: ${req.method} ${req.url}`);
  next();
});

// Serve index.html for all unmatched routes
app.get('*', (req, res) => {
  const indexPath = path.join(publicPath, 'index.html');
  if (fs.existsSync(indexPath)) {
    console.log(`Serving index.html for ${req.url}`);
    res.sendFile(indexPath);
  } else {
    console.error(`FATAL: index.html not found at: ${indexPath}`);
    res.status(404).send('index.html not found');
  }
});

let webClients = new Set();
let androidClients = new Set();

io.on('connection', socket => {
  console.log('Client connected:', socket.id);

  socket.emit('id', socket.id);

  socket.on('identify', (type) => {
    console.log(`Client ${socket.id} identified as: ${type}`);
    if (type === 'web') {
      webClients.add(socket.id);
      androidClients.forEach(androidId => {
        console.log(`Notifying Android ${androidId} about web client ${socket.id}`);
        io.to(androidId).emit('web-client-ready', socket.id);
        io.to(socket.id).emit('android-client-ready', androidId);
      });
    } else if (type === 'android') {
      androidClients.add(socket.id);
      webClients.forEach(webId => {
        console.log(`Notifying Android ${socket.id} about web client ${webId}`);
        socket.emit('web-client-ready', webId);
        io.to(webId).emit('android-client-ready', webId);
      });
    }
    console.log(`Clients - Web: ${webClients.size}, Android: ${androidClients.size}`);
  });

  socket.on('web-client-ready', (id) => {
    console.log(`Web client ${id} announced readiness`);
    webClients.add(id);
    androidClients.forEach(androidId => {
      console.log(`Notifying Android ${androidId} about web client ${id}`);
      io.to(androidId).emit('web-client-ready', id);
    });
  });

  socket.on('signal', data => {
    console.log(`Relaying signal from ${data.from} to ${data.to}: ${data.signal.type || 'candidate'}`);
    console.log(`Signal content: ${JSON.stringify(data.signal)}`);
    if (data.to && io.sockets.sockets.get(data.to)) {
      io.to(data.to).emit('signal', data);
      console.log(`Signal delivered to ${data.to}`);
    } else {
      console.log(`Recipient ${data.to} not found`);
      socket.emit('error', { message: 'Recipient not found', code: 'RECIPIENT_NOT_FOUND' });
    }
  });

  socket.on('disconnect', () => {
    console.log('Client disconnected:', socket.id);
    const wasWeb = webClients.delete(socket.id);
    const wasAndroid = androidClients.delete(socket.id);
    if (wasWeb) {
      androidClients.forEach(androidId => {
        io.to(androidId).emit('web-client-disconnected', socket.id);
      });
    }
    if (wasAndroid) {
      webClients.forEach(webId => {
        io.to(webId).emit('android-client-disconnected', socket.id);
      });
    }
    console.log(`Clients - Web: ${webClients.size}, Android: ${androidClients.size}`);
  });

  socket.on('error', (error) => {
    console.error(`Socket error from ${socket.id}:`, error);
  });
});

server.on('error', (error) => {
  console.error('Server error:', error);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running at http://localhost:${PORT}`);
});

process.on('SIGINT', () => {
  console.log('\nShutting down server...');
  server.close(() => {
    console.log('Server shut down gracefully');
    process.exit(0);
  });
});