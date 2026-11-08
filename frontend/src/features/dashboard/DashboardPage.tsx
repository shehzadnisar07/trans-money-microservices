import { useEffect, useState } from 'react';
import { apiClient } from '../../api/client';
import { transactionApi } from '../../api/transactionApi';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import type { TransactionResponse } from '../../types/transaction';
import {
  Eye, EyeOff, Copy, Bell, Send, History,
  Receipt, Plus, ArrowUpRight, ArrowDownLeft,
  ChevronRight, Wallet, Sparkles, RefreshCw,
  CheckCircle2, TrendingUp
} from 'lucide-react';

interface Account {
  id: string;
  accountNumber: string;
  accountHolderName: string;
  email: string;
  phone: string;
  accountType: string;
  accountStatus: string;
  balance: number;
  dailyTransactionLimit: number;
}

const fmt = (n: number) =>
  'Rs. ' + n.toLocaleString('en-PK', { minimumFractionDigits: 2 });

export const DashboardPage = () => {
  const { user } = useAuthStore();
  const [account, setAccount] = useState<Account | null>(null);
  const [transactions, setTransactions] = useState<TransactionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [balanceVisible, setBalanceVisible] = useState(true);
  const [copied, setCopied] = useState(false);

  useEffect(() => { fetchAll(); }, []);

  const fetchAll = async () => {
    try {
      setLoading(true);
      const accRes = await apiClient.get<Account>('/api/v1/accounts/me');
      setAccount(accRes.data);
      const txData = await transactionApi.getTransactionHistory(accRes.data.accountNumber);
      setTransactions(txData.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 4));
    } catch { /* user may not have an account yet */ }
    finally { setLoading(false); }
  };

  const copyAccNumber = () => {
    if (account) {
      navigator.clipboard.writeText(account.accountNumber);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const displayName = account?.accountHolderName || user?.email?.split('@')[0] || 'User';

  if (loading) return (
    <div className="animate-pulse space-y-6">
      <div className="h-10 w-64 bg-border rounded-xl" />
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        <div className="lg:col-span-3 h-52 bg-border rounded-card" />
        <div className="lg:col-span-2 h-52 bg-border rounded-card" />
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        <div className="lg:col-span-3 h-64 bg-border rounded-card" />
        <div className="lg:col-span-2 h-64 bg-border rounded-card" />
      </div>
    </div>
  );

  if (!account) return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] gap-6">
      <div className="w-20 h-20 bg-blueLt rounded-full flex items-center justify-center">
        <Wallet className="h-10 w-10 text-primary" strokeWidth={1.5} />
      </div>
      <div className="text-center">
        <h2 className="text-2xl font-bold text-navy mb-2">Welcome to TransMoney!</h2>
        <p className="text-body mb-6">Open your first banking account to get started.</p>
        <Link to="/create-account" className="btn-primary inline-flex items-center gap-2">
          <Plus className="h-4 w-4" /> Open Account
        </Link>
      </div>
    </div>
  );

  return (
    <div className="space-y-7">

      {/* ── HEADER ─────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm text-muted font-medium mb-1">Welcome back,</p>
          <h1 className="text-3xl font-bold text-navy tracking-tight">
            {displayName} <span>👋</span>
          </h1>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={fetchAll}
            className="p-2.5 rounded-xl border border-border bg-white text-muted hover:text-primary hover:border-primary-light transition-colors"
          >
            <RefreshCw className="h-4 w-4" />
          </button>
          <button className="relative p-2.5 rounded-xl border border-border bg-white text-muted hover:text-primary hover:border-primary-light transition-colors">
            <Bell className="h-4 w-4" />
            <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-primary rounded-full" />
          </button>
        </div>
      </div>

      {/* ── ROW 1: Balance Card + Quick Actions ──────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* Balance Card */}
        <div className="lg:col-span-3 relative rounded-card overflow-hidden p-8 text-white"
          style={{ background: 'linear-gradient(135deg, #3B6FF5 0%, #4D7DF7 100%)' }}>
          {/* subtle decorative blobs */}
          <div className="absolute -right-12 -top-12 w-48 h-48 bg-white/10 rounded-full" />
          <div className="absolute -right-4 top-16 w-28 h-28 bg-white/5 rounded-full" />
          <div className="absolute -left-6 -bottom-10 w-36 h-36 bg-primary-dark/40 rounded-full" />

          <div className="relative z-10">
            {/* Top row */}
            <div className="flex items-start justify-between mb-7">
              <div className="flex items-center gap-2">
                <p className="text-xs uppercase tracking-widest font-semibold text-white/80">Available Balance</p>
                <button onClick={() => setBalanceVisible(!balanceVisible)} className="text-white/60 hover:text-white transition-colors">
                  {balanceVisible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <span className="flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-white/15 backdrop-blur-sm border border-white/20">
                <span className="w-1.5 h-1.5 rounded-full bg-[#36D58A]" />
                {account.accountStatus}
              </span>
            </div>

            {/* Balance */}
            <p className="text-5xl font-bold tracking-tight mb-9">
              {balanceVisible ? fmt(account.balance) : 'Rs. ••••••'}
            </p>

            {/* Bottom */}
            <div className="flex items-end justify-between">
              <div>
                <p className="text-[11px] uppercase tracking-widest text-white/70 font-semibold mb-1">Account Holder</p>
                <p className="text-base font-semibold">{account.accountHolderName}</p>
              </div>
              <div className="text-right">
                <p className="text-[11px] uppercase tracking-widest text-white/70 font-semibold mb-1">Account Number</p>
                <div className="flex items-center gap-2 justify-end">
                  <p className="text-base font-semibold tracking-widest font-mono">
                    {account.accountNumber.replace(/(.{4})/g, '$1 ').trim()}
                  </p>
                  <button onClick={copyAccNumber} className="text-white/60 hover:text-white transition-colors">
                    {copied ? <CheckCircle2 className="h-4 w-4 text-[#36D58A]" /> : <Copy className="h-4 w-4" />}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Quick Actions */}
        <div className="lg:col-span-2 card p-7 flex flex-col">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-bold text-navy">Quick Actions</h3>
            <Link to="/transactions" className="text-xs font-semibold text-primary hover:text-primary-dark">View All &rsaquo;</Link>
          </div>
          <div className="grid grid-cols-2 gap-4 flex-1">
            {[
              { label: 'Transfer',  sub: 'Send Money',     icon: Send,    bg: 'bg-green-light',    ic: 'text-green',   href: '/transfer' },
              { label: 'History',   sub: 'View Records',   icon: History, bg: 'bg-blueLt',         ic: 'text-primary', href: '/transactions' },
              { label: 'Bill Pay',  sub: 'Pay Bills',      icon: Receipt, bg: 'bg-purple-light',   ic: 'text-purple',  href: '#' },
              { label: 'Add Money', sub: 'Top Up Account', icon: Plus,    bg: 'bg-orange-light',   ic: 'text-orange',  href: '#' },
            ].map(({ label, sub, icon: Icon, bg, ic, href }) => (
              <Link key={label} to={href} className="action-btn group">
                <div className={`${bg} p-3.5 rounded-full mb-1 group-hover:scale-105 transition-transform duration-200`}>
                  <Icon className={`h-5 w-5 ${ic}`} strokeWidth={1.8} />
                </div>
                <p className="text-sm font-bold text-navy">{label}</p>
                <p className="text-xs text-muted">{sub}</p>
              </Link>
            ))}
          </div>
        </div>
      </div>

      {/* ── ROW 2: Accounts Overview + Recent Transactions ──── */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

        {/* Accounts Overview */}
        <div className="lg:col-span-2 card p-7 flex flex-col">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-bold text-navy">Accounts Overview</h3>
          </div>

          <div className="space-y-0 divide-y divide-border flex-1">
            {/* Current Account */}
            <div className="flex items-center gap-4 py-4">
              <div className="w-10 h-10 rounded-xl bg-blueLt flex items-center justify-center flex-shrink-0">
                <Wallet className="h-5 w-5 text-primary" strokeWidth={1.8} />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-navy capitalize">{account.accountType.toLowerCase()} Account</p>
                <p className="text-xs text-muted font-mono mt-0.5">{account.accountNumber.replace(/(.{4})/g, '$1 ').trim()}</p>
              </div>
              <div className="text-right flex-shrink-0">
                <p className="text-sm font-bold text-navy">{balanceVisible ? fmt(account.balance) : '••••'}</p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted flex-shrink-0" />
            </div>

            {/* Placeholder rows for design completeness */}
            <div className="flex items-center gap-4 py-4 opacity-40">
              <div className="w-10 h-10 rounded-xl bg-green-light flex items-center justify-center flex-shrink-0">
                <TrendingUp className="h-5 w-5 text-green" strokeWidth={1.8} />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-navy">Savings Account</p>
                <p className="text-xs text-muted font-mono mt-0.5">—</p>
              </div>
              <div className="text-right flex-shrink-0">
                <p className="text-xs text-muted">Not opened</p>
              </div>
              <ChevronRight className="h-4 w-4 text-muted flex-shrink-0" />
            </div>
          </div>
        </div>

        {/* Recent Transactions */}
        <div className="lg:col-span-3 card p-7 flex flex-col">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-bold text-navy">Recent Transactions</h3>
            <Link to="/transactions" className="text-xs font-semibold text-primary hover:text-primary-dark">View All &rsaquo;</Link>
          </div>

          {transactions.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 py-10 text-center">
              <Receipt className="h-10 w-10 text-muted" strokeWidth={1.3} />
              <p className="text-sm font-semibold text-dark">No transactions yet</p>
              <p className="text-xs text-muted">Make your first transfer to see history here.</p>
            </div>
          ) : (
            <ul className="divide-y divide-border flex-1">
              {transactions.map((tx) => {
                const isOut = tx.senderAccountNumber === account.accountNumber;
                const partyName = isOut
                  ? (tx.receiverName || tx.receiverAccountNumber)
                  : (tx.senderName || tx.senderAccountNumber);

                return (
                  <li key={tx.id}>
                    <Link to={`/transactions/${tx.id}`} className="flex items-center gap-4 py-4 hover:bg-bg rounded-xl px-2 -mx-2 transition-colors">
                      <div className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${isOut ? 'bg-red-50' : 'bg-green-light'}`}>
                        {isOut
                          ? <ArrowUpRight className="h-5 w-5 text-error" strokeWidth={1.8} />
                          : <ArrowDownLeft className="h-5 w-5 text-green" strokeWidth={1.8} />}
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-semibold text-navy truncate">
                          {isOut ? `To ${partyName}` : `From ${partyName}`}
                        </p>
                        <p className="text-xs text-muted mt-0.5">
                          {new Date(tx.createdAt).toLocaleDateString('en-PK', { month: 'short', day: 'numeric' })} &bull; {tx.status}
                        </p>
                      </div>
                      <div className="text-right flex-shrink-0">
                        <p className={`text-sm font-bold ${isOut ? 'text-error' : 'text-green-success'}`}>
                          {isOut ? '−' : '+'} {fmt(tx.amount)}
                        </p>
                        <p className="text-xs text-muted mt-0.5">{new Date(tx.createdAt).toLocaleTimeString('en-PK', { hour: '2-digit', minute: '2-digit' })}</p>
                      </div>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>

      {/* ── SAVINGS BANNER ───────────────────────────────────── */}
      <div className="rounded-card p-7 flex items-center justify-between gap-6 overflow-hidden"
        style={{ background: 'linear-gradient(135deg, #EEF5FF 0%, #F3F0FF 100%)' }}>
        <div className="flex items-center gap-5">
          <div className="w-14 h-14 rounded-2xl bg-white/70 flex items-center justify-center shadow-sm flex-shrink-0">
            <Sparkles className="h-7 w-7 text-primary" strokeWidth={1.6} />
          </div>
          <div>
            <p className="text-base font-bold text-navy">Secure your future with smart savings.</p>
            <p className="text-sm text-body mt-1">Start saving today and achieve your goals.</p>
          </div>
        </div>
        <button className="flex-shrink-0 flex items-center gap-2 bg-white text-primary text-sm font-semibold px-5 py-2.5 rounded-btn shadow-sm hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-200">
          Start Saving <ChevronRight className="h-4 w-4" />
        </button>
      </div>

    </div>
  );
};
