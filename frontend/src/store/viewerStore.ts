import { create } from 'zustand';
import type { ModelMetadata, TreeNode } from '../types/model';
import { api } from '../services/api';

export type DisplayMode = 'all' | 'subtree' | 'leaf';
export type BBoxStyle = 'solid' | 'wireframe';

interface ViewerState {
  metadata: ModelMetadata | null;
  tree: TreeNode | null;
  uploading: boolean;
  uploadProgress: number;      // file upload %
  bboxProgress: string | null; // "12/6580" from SSE during OCCT bbox
  loading: boolean;
  error: string | null;
  /** Primary (single) selection — drives highlight + inspector. */
  selectedId: string | null;
  /** Multi-selection set (for merge). */
  multiSelected: Set<string>;
  search: string;
  displayMode: DisplayMode;
  bboxStyle: BBoxStyle;
  expanded: Set<string>;

  upload: (file: File) => Promise<void>;
  loadCachedModel: (modelId: string) => Promise<void>;
  loadTree: (modelId: string) => Promise<void>;
  watchBboxProgress: (modelId: string) => void;
  select: (id: string | null) => void;
  toggleMulti: (id: string) => void;
  rangeSelect: (id: string) => void;
  setSearch: (s: string) => void;
  setDisplayMode: (m: DisplayMode) => void;
  setBboxStyle: (s: BBoxStyle) => void;
  toggleExpand: (id: string) => void;
  renameNode: (id: string, newName: string) => Promise<void>;
  mergeSelected: () => Promise<void>;
  deleteMergeGroup: (groupId: string) => Promise<void>;
  exportStep: () => Promise<void>;
  reset: () => void;
}

export const useViewerStore = create<ViewerState>((set, get) => ({
  metadata: null,
  tree: null,
  uploading: false,
  uploadProgress: 0,
  bboxProgress: null,
  loading: false,
  error: null,
  selectedId: null,
  multiSelected: new Set<string>(),
  search: '',
  displayMode: 'all',
  bboxStyle: 'solid',
  expanded: new Set<string>(),

  upload: async (file: File) => {
    set({ uploading: true, uploadProgress: 0, bboxProgress: null, error: null, tree: null, metadata: null });
    try {
      const meta = await api.uploadStep(file, (pct) => set({ uploadProgress: pct }));
      set({ metadata: meta, uploading: false });
      // Watch bbox progress via SSE.
      get().watchBboxProgress(meta.id);
      await get().loadTree(meta.id);
    } catch (e: any) {
      set({ uploading: false, error: e?.response?.data?.detail ?? String(e) });
    }
  },

  loadCachedModel: async (modelId: string) => {
    set({ uploading: true, uploadProgress: 100, bboxProgress: null, error: null, tree: null, metadata: null });
    try {
      const { default: axios } = await import('axios');
      const resp = await axios.post(`/api/models/cached/${modelId}/load`);
      const meta = resp.data;
      set({ metadata: meta, uploading: false });
      await get().loadTree(meta.id);
    } catch (e: any) {
      set({ uploading: false, error: e?.response?.data?.detail ?? String(e) });
    }
  },

  watchBboxProgress: (modelId: string) => {
    set({ bboxProgress: '0/?' });
    // Poll the REST progress endpoint every 2 seconds.
    const poll = async () => {
      try {
        const resp = await fetch(`/api/models/${modelId}/progress`);
        const data = await resp.json();
        const prog = data.progress;
        if (prog && prog !== 'unknown') {
          set({ bboxProgress: prog });
          if (prog !== 'done') {
            setTimeout(poll, 2000);
          }
        } else {
          setTimeout(poll, 2000);
        }
      } catch {
        setTimeout(poll, 2000);
      }
    };
    setTimeout(poll, 1000);
  },

  loadTree: async (modelId: string) => {
    set({ loading: true, error: null });
    try {
      const tree = await api.getTree(modelId);
      const expanded = new Set<string>();
      // Auto-expand top 2 levels for a usable initial view.
      const walk = (n: TreeNode, depth: number) => {
        if (depth < 2) { expanded.add(n.id); n.children.forEach((c) => walk(c, depth + 1)); }
      };
      walk(tree, 0);
      set({ tree, loading: false, selectedId: tree.id, multiSelected: new Set(), expanded });
    } catch (e: any) {
      set({ loading: false, error: e?.response?.data?.detail ?? String(e) });
    }
  },

  select: (id) => set({ selectedId: id }),
  toggleMulti: (id) => set((s) => {
    const next = new Set(s.multiSelected);
    if (next.has(id)) next.delete(id); else next.add(id);
    return { multiSelected: next, selectedId: id };
  }),
  rangeSelect: (id) => set((s) => {
    const next = new Set(s.multiSelected);
    next.add(id);
    return { multiSelected: next, selectedId: id };
  }),
  setSearch: (s) => set({ search: s }),
  setDisplayMode: (m) => set({ displayMode: m }),
  setBboxStyle: (bs) => set({ bboxStyle: bs }),
  toggleExpand: (id) => set((s) => {
    const next = new Set(s.expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    return { expanded: next };
  }),

  renameNode: async (id, newName) => {
    const { metadata } = get();
    if (!metadata) return;
    await api.renameNode(metadata.id, id, newName);
    await get().loadTree(metadata.id);
  },

  mergeSelected: async () => {
    const { metadata, multiSelected } = get();
    if (!metadata || multiSelected.size < 2) return;
    await api.createMergeGroup(metadata.id, [...multiSelected]);
    set({ multiSelected: new Set() });
    await get().loadTree(metadata.id);
  },

  deleteMergeGroup: async (groupId) => {
    const { metadata } = get();
    if (!metadata) return;
    await api.deleteMergeGroup(metadata.id, groupId);
    await get().loadTree(metadata.id);
  },

  exportStep: async () => {
    const { metadata } = get();
    if (!metadata) return;
    const blob = await api.exportStep(metadata.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'skeleton.stp'; a.click();
    URL.revokeObjectURL(url);
  },

  reset: () =>
    set({ metadata: null, tree: null, uploading: false, uploadProgress: 0, loading: false, error: null, selectedId: null, multiSelected: new Set(), search: '', expanded: new Set() }),
}));
