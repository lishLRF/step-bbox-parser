import React from 'react';
import ReactDOM from 'react-dom/client';
import { ViewerPage } from './pages/ViewerPage';
import './styles/index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ViewerPage />
  </React.StrictMode>,
);
