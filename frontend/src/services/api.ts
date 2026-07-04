import axios from 'axios';
import type { ModelMetadata, ParsedModel, TreeNode } from '../types/model';

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api',
  timeout: 120_000,
});

export const api = {
  async uploadStep(file: File, onProgress?: (pct: number) => void): Promise<ModelMetadata> {
    const form = new FormData();
    form.append('file', file);
    const { data } = await http.post<ModelMetadata>('/models/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) =>
        onProgress?.(e.total ? Math.round((e.loaded * 100) / e.total) : 0),
    });
    return data;
  },

  async getTree(modelId: string): Promise<TreeNode> {
    const { data } = await http.get<TreeNode>(`/models/${modelId}/tree`);
    return data;
  },

  async getMetadata(modelId: string): Promise<ModelMetadata> {
    const { data } = await http.get<ModelMetadata>(`/models/${modelId}/metadata`);
    return data;
  },

  async loadFull(modelId: string): Promise<ParsedModel> {
    const [metadata, root] = await Promise.all([
      this.getMetadata(modelId),
      this.getTree(modelId),
    ]);
    return { metadata, root };
  },

  async delete(modelId: string): Promise<void> {
    await http.delete(`/models/${modelId}`);
  },
};
