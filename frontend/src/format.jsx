import React from 'react';

export function fmtInr(value, decimals = 2) {
  const n = Number(value ?? 0);
  const abs = Math.abs(n);
  const formatted = abs.toLocaleString('en-IN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  });
  return (n < 0 ? '-₹' : '₹') + formatted;
}

export function fmtCompactInr(value) {
  const n = Number(value ?? 0);
  const hasPaise = Math.round((Math.abs(n) % 1) * 100) !== 0;
  return fmtInr(n, hasPaise ? 2 : 0);
}

export function BankEmblem({ name = '', size = 'md' }) {
  const low = name.toLowerCase();
  const dims = size === 'sm' ? 'w-6 h-6 text-[10px] rounded-lg' : 'w-9 h-9 text-xs rounded-xl';

  if (low.includes('icici')) {
    return (
      <span
        title={name}
        className={`${dims} inline-flex items-center justify-center bg-orange-50 text-orange-600 border border-orange-200/80 font-extrabold shrink-0`}
      >
        iC
      </span>
    );
  }
  if (low.includes('idfc')) {
    return (
      <span
        title={name}
        className={`${dims} inline-flex items-center justify-center bg-rose-50 text-rose-800 border border-rose-200/90 font-extrabold shrink-0`}
      >
        ID
      </span>
    );
  }
  if (low.includes('hdfc')) {
    return (
      <span
        title={name}
        className={`${dims} inline-flex items-center justify-center bg-blue-50 text-blue-800 border border-blue-200/90 font-extrabold shrink-0`}
      >
        HD
      </span>
    );
  }
  if (low.includes('uco')) {
    return (
      <span
        title={name}
        className={`${dims} inline-flex items-center justify-center bg-sky-50 text-sky-700 border border-sky-200/90 font-extrabold shrink-0`}
      >
        UC
      </span>
    );
  }
  return (
    <span
      title={name}
      className={`${dims} inline-flex items-center justify-center bg-slate-100 text-navy-900 border border-slate-200 font-bold shrink-0`}
    >
      {(name || 'BK').slice(0, 2).toUpperCase()}
    </span>
  );
}

export function MarkdownLite({ text = '' }) {
  const lines = String(text).split('\n');
  const blocks = [];
  let currentList = [];

  const formatInline = (str) => {
    const parts = str.split(/(\*\*[^*]+\*\*)/g);
    return parts.map((part, i) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        return (
          <strong key={i} className="font-bold text-navy-950">
            {part.slice(2, -2)}
          </strong>
        );
      }
      return <React.Fragment key={i}>{part}</React.Fragment>;
    });
  };

  lines.forEach((raw, idx) => {
    const line = raw.trim();
    if (!line) {
      if (currentList.length) {
        blocks.push(
          <ul key={`ul-${idx}`} className="list-disc pl-5 space-y-1.5 my-2 text-slate-700">
            {currentList}
          </ul>
        );
        currentList = [];
      }
      return;
    }
    if (line.startsWith('- ') || /^\d+\.\s+/.test(line)) {
      const cleaned = line.replace(/^(-\s+|\d+\.\s+)/, '');
      currentList.push(<li key={`li-${idx}`}>{formatInline(cleaned)}</li>);
    } else {
      if (currentList.length) {
        blocks.push(
          <ul key={`ul-${idx}`} className="list-disc pl-5 space-y-1.5 my-2 text-slate-700">
            {currentList}
          </ul>
        );
        currentList = [];
      }
      blocks.push(
        <p key={`p-${idx}`} className="my-1.5 text-slate-800 leading-relaxed">
          {formatInline(line)}
        </p>
      );
    }
  });

  if (currentList.length) {
    blocks.push(
      <ul key="ul-final" className="list-disc pl-5 space-y-1.5 my-2 text-slate-700">
        {currentList}
      </ul>
    );
  }

  return <div className="text-sm">{blocks}</div>;
}
