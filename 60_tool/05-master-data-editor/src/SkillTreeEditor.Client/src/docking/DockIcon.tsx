const paths = {
  sidebar: 'M4 4.5h16a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 18V6A1.5 1.5 0 0 1 4 4.5ZM9 4.5v15',
  windows: 'M7.5 4.5h11a1.5 1.5 0 0 1 1.5 1.5v9M5 8h10a1.5 1.5 0 0 1 1.5 1.5V18a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V9.5A1.5 1.5 0 0 1 5 8Z',
  chevron: 'm7 10 5 5 5-5',
  folder: 'M3.5 7V6A1.5 1.5 0 0 1 5 4.5h4l2 2h8A1.5 1.5 0 0 1 20.5 8v10a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V7Z',
}

export function DockIcon({ name }: { name: keyof typeof paths | 'more' }) {
  return <svg className="dock-icon" viewBox="0 0 24 24" aria-hidden="true">
    {name === 'more'
      ? <path d="M5 12h.01M12 12h.01M19 12h.01" strokeWidth="2.8" />
      : <path d={paths[name]} />}
  </svg>
}
