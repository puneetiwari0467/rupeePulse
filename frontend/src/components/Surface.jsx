import React, { useEffect, useId, useRef, useState } from 'react';
import { LoaderCircle, X } from 'lucide-react';

export function Dialog({ title, kicker = 'DEMO WORKSPACE', onClose, children, drawer = false }) {
  const ref = useRef(null);
  const id = useId();
  useEffect(() => {
    const el = ref.current;
    const previous = document.activeElement;
    el.showModal();
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { el.close(); document.body.style.overflow = overflow; previous?.focus?.(); };
  }, []);
  return <dialog ref={ref} aria-labelledby={id} className={`dialog ${drawer ? 'drawer' : ''}`}
    onCancel={event => { event.preventDefault(); onClose(); }}>
    <div className="dialog-head"><div><p className="eyebrow">{kicker}</p><h2 id={id}>{title}</h2></div>
      <button className="icon-btn" aria-label={`Close ${title}`} onClick={onClose}><X size={18}/></button></div>
    {children}
  </dialog>;
}

export function Editor({ title, note, fields = [], submitLabel = 'Save', onSave, onClose, children }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  async function submit(event) {
    event.preventDefault();
    if (inFlight.current) return;
    const values = Object.fromEntries(new FormData(event.currentTarget).entries());
    inFlight.current = true; setBusy(true); setError('');
    try {
      for (const field of fields) if (field.type === 'number') values[field.name] = Number(values[field.name]);
      await onSave(values); onClose();
    } catch (e) { setError(e.message); }
    finally { inFlight.current = false; setBusy(false); }
  }
  return <Dialog title={title} onClose={() => { if (!inFlight.current) onClose(); }}>
    <form className="editor" onSubmit={submit}>
      {note && <p className="muted">{note}</p>}
      {fields.map(f => <label key={f.name}>{f.label}
        {f.options ? <select name={f.name} defaultValue={f.value} required>
          {f.options.map(opt => <option key={opt.value ?? opt} value={opt.value ?? opt}>{opt.label ?? opt}</option>)}</select>
          : <input name={f.name} type={f.type || 'text'} defaultValue={f.value ?? ''} required={f.required !== false}
            pattern={f.pattern} maxLength={f.maxLength ?? 150} min={f.min} step={f.type==='number' ? '0.01' : undefined}
            placeholder={f.placeholder} autoComplete="off"/>}</label>)}
      {children}
      {error && <p role="alert" className="error-note">{error}</p>}
      <div className="form-actions"><button type="button" className="btn quiet" disabled={busy} onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy}>{busy && <LoaderCircle className="spin" size={15}/>} {submitLabel}</button></div>
    </form>
  </Dialog>;
}

export function Intro({ eyebrow, title, description, children }) {
  return <div className="intro"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1>
    {description && <p className="muted">{description}</p>}</div>{children && <div className="actions">{children}</div>}</div>;
}

export function Segments({ label, items, value, onChange }) {
  return <div className="segments" role="group" aria-label={label}>
    {items.map(([id, name]) => <button type="button" key={id} aria-pressed={value === id} onClick={() => onChange(id)}>{name}</button>)}
  </div>;
}

export function Empty({ title, text, children }) {
  return <div className="empty"><strong>{title}</strong><p>{text}</p>{children}</div>;
}
