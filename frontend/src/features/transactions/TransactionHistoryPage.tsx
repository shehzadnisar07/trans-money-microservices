import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { transactionApi } from '../../api/transactionApi';
import { apiClient } from '../../api/client';
import type { TransactionResponse } from '../../types/transaction';
import { ChevronRight, ArrowDownLeft, ArrowUpRight, Clock, AlertCircle } from 'lucide-react';

export const TransactionHistoryPage = () => {
  const [transactions, setTransactions] = useState<TransactionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [accountNumber, setAccountNumber] = useState<string | null>(null);

  useEffect(() => {
    const fetchHistory = async () => {
      try {
        setLoading(true);
        const accResponse = await apiClient.get('/api/v1/accounts/me');
        const accNo = accResponse.data.accountNumber;
        setAccountNumber(accNo);
        const txData = await transactionApi.getTransactionHistory(accNo);
        setTransactions(txData.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()));
      } catch (err) {
        setError('Failed to load transaction history.');
      } finally {
        setLoading(false);
      }
    };
    fetchHistory();
  }, []);

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-3xl font-bold text-navy tracking-tight">Transaction History</h1>
          <p className="mt-2 text-sm text-body">Review your recent financial activity</p>
        </div>
        
        <div className="card">
          <div className="px-6 py-4 border-b border-border">
            <div className="h-5 bg-border rounded w-32 animate-pulse"></div>
          </div>
          <div className="divide-y divide-border">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="flex items-center gap-4 py-5 px-6">
                <div className="w-11 h-11 bg-border rounded-full animate-pulse flex-shrink-0"></div>
                <div className="flex-1 space-y-2">
                  <div className="h-4 bg-border rounded w-1/4 animate-pulse"></div>
                  <div className="h-3 bg-border rounded w-1/6 animate-pulse"></div>
                </div>
                <div className="text-right space-y-2">
                  <div className="h-4 bg-border rounded w-20 animate-pulse ml-auto"></div>
                  <div className="h-3 bg-border rounded w-12 animate-pulse ml-auto"></div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-3xl font-bold text-navy tracking-tight">Transaction History</h1>
          <p className="mt-2 text-sm text-body">Review your recent financial activity</p>
        </div>
        <div className="card p-8 flex flex-col items-center justify-center text-center">
          <div className="w-16 h-16 rounded-full bg-red-50 flex items-center justify-center mb-4">
            <AlertCircle className="w-8 h-8 text-error" />
          </div>
          <h3 className="text-lg font-semibold text-navy mb-2">Error Loading History</h3>
          <p className="text-body max-w-md">{error}</p>
          <button 
            onClick={() => window.location.reload()}
            className="mt-6 btn-primary"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  if (transactions.length === 0) {
    return (
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-3xl font-bold text-navy tracking-tight">Transaction History</h1>
          <p className="mt-2 text-sm text-body">Review your recent financial activity</p>
        </div>
        <div className="card py-16 px-6 flex flex-col items-center justify-center text-center">
          <div className="bg-blueLt rounded-full p-4 mb-4">
            <Clock className="w-8 h-8 text-primary" />
          </div>
          <h3 className="text-xl font-bold text-navy mb-2">No Transactions Yet</h3>
          <p className="text-body max-w-sm mb-6">You haven't made any transactions yet. Your activity will appear here.</p>
          <Link to="/transfer" className="btn-primary">
            Make a Transfer
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-navy tracking-tight">Transaction History</h1>
          <p className="mt-2 text-sm text-body">Review your recent financial activity</p>
        </div>
        <div>
          <Link to="/transfer" className="btn-primary">
            New Transfer
          </Link>
        </div>
      </div>

      <div className="card overflow-hidden">
        <div className="px-6 py-4 border-b border-border bg-bg/50">
          <h2 className="text-sm font-semibold text-navy">All Transactions</h2>
        </div>
        
        <div className="divide-y divide-border">
          {transactions.map((tx) => {
            const isOutgoing = tx.senderAccountNumber === accountNumber;
            const partyName = isOutgoing
              ? (tx.receiverName || tx.receiverAccountNumber)
              : (tx.senderName || tx.senderAccountNumber);
            
            return (
              <Link 
                key={tx.id} 
                to={`/transactions/${tx.id}`}
                className="flex items-center gap-4 py-5 px-6 hover:bg-bg transition-colors group"
              >
                <div className="flex-shrink-0">
                  {isOutgoing ? (
                    <div className="w-11 h-11 rounded-full bg-red-50 flex items-center justify-center">
                      <ArrowUpRight className="w-5 h-5 text-error" />
                    </div>
                  ) : (
                    <div className="w-11 h-11 rounded-full bg-green-light flex items-center justify-center">
                      <ArrowDownLeft className="w-5 h-5 text-green" />
                    </div>
                  )}
                </div>
                
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-navy truncate">
                    {isOutgoing ? `Transfer to ${partyName}` : `Transfer from ${partyName}`}
                  </p>
                  <div className="flex items-center gap-2 mt-0.5">
                    <p className="text-xs text-muted">
                      {new Date(tx.createdAt).toLocaleDateString(undefined, { 
                        month: 'short', 
                        day: 'numeric',
                        year: 'numeric'
                      })}
                    </p>
                    {tx.status === 'PENDING_VERIFICATION' && (
                      <>
                        <span className="w-1 h-1 rounded-full bg-muted"></span>
                        <div className="flex items-center gap-1">
                          <AlertCircle className="w-3 h-3 text-orange" />
                          <span className="text-xs text-orange font-semibold">Pending</span>
                        </div>
                      </>
                    )}
                  </div>
                </div>
                
                <div className="text-right">
                  <p className={`text-sm font-bold ${isOutgoing ? 'text-error' : 'text-green-success'}`}>
                    {isOutgoing ? '-' : '+'}${tx.amount.toFixed(2)}
                  </p>
                  <p className="text-xs text-muted mt-0.5">
                    {new Date(tx.createdAt).toLocaleTimeString(undefined, { 
                      hour: 'numeric', 
                      minute: '2-digit'
                    })}
                  </p>
                </div>
                
                <div className="ml-2 flex-shrink-0 text-muted group-hover:text-primary transition-colors">
                  <ChevronRight className="w-5 h-5" />
                </div>
              </Link>
            );
          })}
        </div>
      </div>
    </div>
  );
};
