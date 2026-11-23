import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { api, useAccount } from '../../api'
import { Empty, ErrorPanel, Pending } from '../../components/ui'

export function useWorkspace<T>(path: string, interval?: number) {
  const { data: account } = useAccount()
  return useQuery({
    queryKey: ['workspace', account?.id, path], queryFn: ({ signal }) => api<T>(path, { signal }), refetchInterval: interval,
  })
}

export function Panel({ title, children, pending, error, retry }: {
  title: string; children: ReactNode; pending?: boolean; error?: unknown; retry?: () => void
}) {
  return <section className="workspace-panel">
    <div className="content-title"><h2>{title}</h2></div>
    {pending && <Pending label={'Loading ' + title.toLowerCase()} />}
    {!!error && <ErrorPanel error={error} retry={retry} />}
    {children}
  </section>
}

export function DataTable({ columns, rows, empty = 'No records yet' }: { columns: string[]; rows: ReactNode[][]; empty?: string }) {
  if (!rows.length) return <Empty title={empty} />
  return <div className="glass table-wrap"><table>
    <thead><tr>{columns.map(column => <th scope="col" key={column}>{column}</th>)}</tr></thead>
    <tbody>{rows.map((row, index) => <tr key={index}>{row.map((cell, i) => <td key={columns[i]}>{cell}</td>)}</tr>)}</tbody>
  </table></div>
}

export function Stats({ items }: { items: [string, ReactNode][] }) {
  return <dl className="stat-grid">{items.map(([label, value]) => <div key={label} className="glass stat">
    <dt>{label}</dt><dd>{value}</dd>
  </div>)}</dl>
}

export function State({ value }: { value: string }) {
  return <span className={'state-pill' + (['REJECTED','EXPIRED','REFUNDED','DISABLED','VOID'].includes(value) ? ' inactive' : '')}>
    {value.replaceAll('_', ' ')}
  </span>
}
