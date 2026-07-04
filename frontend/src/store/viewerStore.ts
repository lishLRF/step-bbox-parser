import { create } from 'zustand';
import type { ModelMetadata, TreeNode } from '../types/model';
import { api } from '../services/api';

interface ViewerState {
  metadata: ModelMetadata | null;
  tree: TreeNode | null;
  uploading: boolean;
  uploadProgress: number;
  loading: boolean;
  error: string | null;
  selectedId: string | null;

  upload: (file: File) => Promise<void>;
  loadTree: (modelId: string) => Promise<void>;
  select: (id: string | null) => void;
  reset: () => void;
}

export const useViewerStore = create<ViewerState>((set, get) => ({
  metadata: null,
  tree: null,
  uploading: false,
  uploadProgress: 0,
  loading: false,
  error: null,
  selectedId: null,

  upload: async (file: File) => {
    set({ uploading: true, uploadProgress: 0, error: null, tree: null, metadata: null });
    try {
      const meta = await api.uploadStep(file, (pct) => set({ uploadProgress: pct }));
      set({ metadata: meta, uploading: false });
      await get().loadTree(meta.id);
    } catch (e: any) {
      set({ uploading: false, error: e?.response?.data?.detail ?? String(e) });
    }
  },

  loadTree: async (modelId: string) => {
    set({ loading: true, error: null });
    try {
      const tree = await api.getTree(modelId);
      set({ tree, loading: false, selectedId: tree.id });
    } catch (e: any) {
      set({ loading: false, error: e?.response?.data?.detail ?? String(e) });
    }
  },

  select: (id) => set({ selectedId: id }),
  reset: () =>
    set({ metadata: null, tree: null, uploading: false, uploadProgress: 0, loading: false, error: null, selectedId: null }),
}));
