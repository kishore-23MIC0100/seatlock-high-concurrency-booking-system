import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, LockKeyhole } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { accountKey, send, useCapabilities } from '../api'
import { ErrorPanel } from '../components/ui'
import { internalRedirect } from '../format'
import type { Account, Role } from '../model'

type Fields = { displayName: string; email: string; password: string }
const fields = (register: boolean) => z.object({
  displayName: z.string().trim().min(register ? 1 : 0, 'Enter your name.').max(100),
  email: z.email('Enter a valid email address.').max(180),
  password: z.string().min(register ? 10 : 1, register ? 'Enter at least 10 characters.' : 'Enter your password.').max(72),
})

export function Auth({ register = false }: { register?: boolean }) {
  const [params] = useSearchParams()
  const next = internalRedirect(params.get('next'))
  const navigate = useNavigate()
  const client = useQueryClient()
  const capabilities = useCapabilities()
  const form = useForm<Fields>({ resolver: zodResolver(fields(register)), defaultValues: { displayName: '', email: '', password: '' } })
  async function complete(account: Account, destination = next) {
    await client.cancelQueries({ queryKey: accountKey })
    client.removeQueries({ predicate: query => ['notifications','my-tickets','my-bookings','ticket','booking','hold','waitlist'].includes(String(query.queryKey[0])) })
    client.setQueryData(accountKey, account)
    navigate(destination, { replace: true })
  }
  const auth = useMutation({
    mutationFn: (body: Fields) => send<Account>('/auth/' + (register ? 'register' : 'login'),
      register ? body : { email: body.email, password: body.password }),
    onSuccess: account => complete(account),
  })
  const demo = useMutation({
    mutationFn: (role: Role) => send<Account>('/auth/demo', { role }),
    onSuccess: account => complete(account, account.role === 'ORGANIZER' ? '/organizer' : account.role === 'ADMIN' ? '/admin' : next),
  })
  const errors = form.formState.errors
  return <main id="main-content" className="page-width page-content auth-page">
    <section className="glass form-card">
      <span className="feature-icon"><LockKeyhole size={21} /></span>
      <h1>{register ? 'Create your account' : 'Sign in to SeatLock'}</h1>
      <form className="form-stack" onSubmit={form.handleSubmit(values => auth.mutate(values))} noValidate>
        {register && <label>Display name<input autoComplete="name" {...form.register('displayName')} />{errors.displayName && <small className="field-error">{errors.displayName.message}</small>}</label>}
        <label>Email address<input type="email" autoComplete="email" {...form.register('email')} />{errors.email && <small className="field-error">{errors.email.message}</small>}</label>
        <label>Password<input type="password" autoComplete={register ? 'new-password' : 'current-password'} {...form.register('password')} />{errors.password && <small className="field-error">{errors.password.message}</small>}</label>
        {auth.error && <ErrorPanel error={auth.error} />}
        <button className="btn btn-primary" type="submit" disabled={auth.isPending || demo.isPending}>
          {auth.isPending ? 'Please wait…' : register ? 'Create account' : 'Sign in'} <ArrowRight size={18} />
        </button>
      </form>
      <Link className="text-link" to={(register ? '/login' : '/register') + '?next=' + encodeURIComponent(next)}>
        {register ? 'Already registered? Sign in' : 'New to SeatLock? Create an account'}
      </Link>
      {capabilities.data?.demo && !register && <div className="demo-options">
        <h2>Local demo access</h2>
        <div className="action-row">
          {(['CUSTOMER', 'ORGANIZER', 'ADMIN'] as const).map(role => <button type="button" key={role} disabled={demo.isPending || auth.isPending}
            className="btn btn-secondary" onClick={() => demo.mutate(role)}>{role === 'CUSTOMER' ? 'Customer demo' : role === 'ADMIN' ? 'Admin demo' : 'Organizer demo'}</button>)}
        </div>
        {demo.error && <ErrorPanel error={demo.error} />}
      </div>}
    </section>
  </main>
}
